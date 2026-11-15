package egress

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/lightwing/blog/wechat-egress/internal/pb"
	"github.com/lightwing/blog/wechat-egress/internal/wechat"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const maxMessage = 2 << 20

// ServerConfig is the process that runs on the fixed-IP VPS.
type ServerConfig struct {
	AppID    string
	Secret   string
	Token    string
	APIBase  string
	Insecure bool
	CertFile string
	KeyFile  string
	Logger   *log.Logger
	HTTP     *http.Client
}

// Server implements the draft gRPC API. It embeds the unimplemented server
// by value so a future method fails closed instead of panicking.
type Server struct {
	pb.UnimplementedWechatEgressServer
	api      *wechat.API
	token    string
	insecure bool
	creds    credentials.TransportCredentials
	log      *log.Logger
}

// NewServer checks credentials and TLS before any socket is opened.
func NewServer(cfg ServerConfig) (*Server, error) {
	token, err := NormalizeToken(cfg.Token)
	if err != nil {
		return nil, err
	}
	secret := strings.TrimSpace(cfg.Secret)
	if subtle.ConstantTimeCompare([]byte(token), []byte(secret)) == 1 {
		return nil, errors.New("WECHAT_EGRESS_TOKEN must not be the WeChat AppSecret")
	}
	var creds credentials.TransportCredentials
	if cfg.Insecure {
		if cfg.CertFile != "" || cfg.KeyFile != "" {
			return nil, errors.New("do not combine insecure mode with a TLS certificate")
		}
	} else {
		if cfg.CertFile == "" || cfg.KeyFile == "" {
			return nil, errors.New("TLS certificate and key are required")
		}
		cert, err := tls.LoadX509KeyPair(cfg.CertFile, cfg.KeyFile)
		if err != nil {
			return nil, fmt.Errorf("load TLS key pair: %w", err)
		}
		creds = credentials.NewTLS(&tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
		})
	}
	api, err := wechat.New(wechat.Config{
		AppID:   cfg.AppID,
		Secret:  cfg.Secret,
		BaseURL: cfg.APIBase,
		HTTP:    cfg.HTTP,
	})
	if err != nil {
		return nil, err
	}
	logger := cfg.Logger
	if logger == nil {
		logger = log.New(io.Discard, "", 0)
	}
	return &Server{api: api, token: token, insecure: cfg.Insecure, creds: creds, log: logger}, nil
}

// ListenAndServe binds addr and serves until ctx is canceled.
func (s *Server) ListenAndServe(ctx context.Context, addr string) error {
	if s.insecure {
		if err := requireLoopbackListen(addr); err != nil {
			return err
		}
	}
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	s.log.Printf("listening on %s", lis.Addr())
	return s.Serve(ctx, lis)
}

// Serve accepts on lis until ctx is canceled.
func (s *Server) Serve(ctx context.Context, lis net.Listener) error {
	if s.insecure && !addrIsLoopback(lis.Addr().String()) {
		lis.Close()
		return errors.New("insecure gRPC is only allowed on a loopback address")
	}
	opts := []grpc.ServerOption{
		grpc.UnaryInterceptor(s.intercept),
		grpc.MaxRecvMsgSize(maxMessage),
		grpc.MaxSendMsgSize(maxMessage),
	}
	if s.creds != nil {
		opts = append(opts, grpc.Creds(s.creds))
	}
	gs := grpc.NewServer(opts...)
	pb.RegisterWechatEgressServer(gs, s)
	errCh := make(chan error, 1)
	go func() {
		errCh <- gs.Serve(lis)
	}()
	select {
	case <-ctx.Done():
		stopped := make(chan struct{})
		go func() {
			gs.GracefulStop()
			close(stopped)
		}()
		select {
		case <-stopped:
		case <-time.After(2 * time.Second):
			gs.Stop()
		}
		return nil
	case err := <-errCh:
		if errors.Is(err, grpc.ErrServerStopped) {
			return nil
		}
		return err
	}
}

func (s *Server) intercept(ctx context.Context, req any, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
	got, err := bearerFrom(ctx)
	if err != nil || !tokenMatch(got, s.token) {
		s.log.Printf("%s rejected", info.FullMethod)
		return nil, status.Error(codes.Unauthenticated, "invalid bearer token")
	}
	resp, err := handler(ctx, req)
	if err != nil {
		s.log.Printf("%s %s", info.FullMethod, status.Convert(err).Message())
	}
	return resp, err
}

func bearerFrom(ctx context.Context) (string, error) {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return "", status.Error(codes.Unauthenticated, "invalid bearer token")
	}
	vals := md.Get("authorization")
	if len(vals) != 1 {
		return "", status.Error(codes.Unauthenticated, "invalid bearer token")
	}
	const prefix = "bearer "
	header := vals[0]
	if len(header) < len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return "", status.Error(codes.Unauthenticated, "invalid bearer token")
	}
	token := strings.TrimSpace(header[len(prefix):])
	if token == "" || strings.ContainsAny(token, " \t") {
		return "", status.Error(codes.Unauthenticated, "invalid bearer token")
	}
	return token, nil
}

func tokenMatch(got, want string) bool {
	if len(got) != len(want) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func (s *Server) GetStableToken(ctx context.Context, _ *pb.GetStableTokenRequest) (*pb.GetStableTokenResponse, error) {
	token, expires, err := s.api.StableToken(ctx)
	if err != nil {
		return nil, toStatus(err)
	}
	return &pb.GetStableTokenResponse{AccessToken: token, ExpiresIn: int32(expires)}, nil
}

func (s *Server) UploadImage(ctx context.Context, req *pb.UploadImageRequest) (*pb.UploadImageResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "missing request")
	}
	url, err := s.api.UploadImage(ctx, req.GetContent(), req.GetFilename())
	if err != nil {
		return nil, toStatus(err)
	}
	return &pb.UploadImageResponse{Url: url}, nil
}

func (s *Server) AddDraft(ctx context.Context, req *pb.AddDraftRequest) (*pb.AddDraftResponse, error) {
	if req == nil {
		return nil, status.Error(codes.InvalidArgument, "missing request")
	}
	articles, err := articlesFromProto(req.GetArticles())
	if err != nil {
		return nil, toStatus(err)
	}
	mediaID, err := s.api.AddDraft(ctx, articles)
	if err != nil {
		return nil, toStatus(err)
	}
	return &pb.AddDraftResponse{MediaId: mediaID}, nil
}

func (s *Server) UpdateDraft(ctx context.Context, req *pb.UpdateDraftRequest) (*pb.UpdateDraftResponse, error) {
	if req == nil || req.GetArticle() == nil {
		return nil, status.Error(codes.InvalidArgument, "article is required")
	}
	article, err := articleFromProto(req.GetArticle())
	if err != nil {
		return nil, toStatus(err)
	}
	if err := s.api.UpdateDraft(ctx, req.GetMediaId(), int(req.GetIndex()), article); err != nil {
		return nil, toStatus(err)
	}
	return &pb.UpdateDraftResponse{}, nil
}

func toStatus(err error) error {
	if err == nil {
		return nil
	}
	if _, ok := status.FromError(err); ok {
		return err
	}
	var invalid *wechat.Invalid
	if errors.As(err, &invalid) {
		return status.Error(codes.InvalidArgument, err.Error())
	}
	var call *wechat.CallError
	if errors.As(err, &call) {
		if call.Kind == "network" {
			return status.Error(codes.Unavailable, call.Error())
		}
		return status.Error(codes.FailedPrecondition, call.Error())
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return status.Error(codes.DeadlineExceeded, "upstream timed out")
	}
	if errors.Is(err, context.Canceled) {
		return status.Error(codes.Canceled, "canceled")
	}
	return status.Error(codes.Internal, "wechat request failed")
}

func requireLoopbackListen(addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("listen address must be host:port")
	}
	if !isLoopbackHost(host) {
		return errors.New("insecure gRPC is only allowed on a loopback address")
	}
	return nil
}

func addrIsLoopback(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	return isLoopbackHost(host)
}

func isLoopbackHost(host string) bool {
	host = strings.TrimSuffix(host, ".")
	if host == "" {
		return false
	}
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
