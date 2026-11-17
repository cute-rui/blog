package egress

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"time"

	"github.com/lightwing/blog/wechat-egress/internal/pb"
	"github.com/lightwing/blog/wechat-egress/internal/srvdns"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

// ClientConfig selects how the CLI finds the VPS.
// Address, when set, skips SRV. Otherwise the client looks up
// SRVName (default _wechat._tcp) under SRVHost.
type ClientConfig struct {
	Token      string
	Address    string
	SRVName    string
	SRVHost    string
	Insecure   bool
	CAFile     string
	ServerName string

	dnsServer string
	resolve   func(context.Context, string, string) ([]srvdns.Endpoint, error)
}

// Client calls the VPS. Each RPC dials the next SRV target when the current
// one is down. The client never sends WeChat credentials.
type Client struct {
	token      string
	endpoints  []srvdns.Endpoint
	insecure   bool
	caFile     string
	serverName string
}

// Dial resolves the target and checks the insecure-mode guard. It does not
// open a connection until an RPC runs.
func Dial(ctx context.Context, cfg ClientConfig) (*Client, error) {
	token, err := NormalizeToken(cfg.Token)
	if err != nil {
		return nil, err
	}
	endpoints, err := cfg.endpoints(ctx)
	if err != nil {
		return nil, err
	}
	if len(endpoints) == 0 {
		return nil, errors.New("no SRV targets")
	}
	if cfg.Insecure {
		for _, ep := range endpoints {
			if !isLoopbackHost(ep.Host) {
				return nil, fmt.Errorf("insecure gRPC is only allowed for loopback targets, not %s", ep.Host)
			}
		}
	}
	return &Client{
		token:      token,
		endpoints:  endpoints,
		insecure:   cfg.Insecure,
		caFile:     cfg.CAFile,
		serverName: cfg.ServerName,
	}, nil
}

func (cfg ClientConfig) endpoints(ctx context.Context) ([]srvdns.Endpoint, error) {
	if cfg.Address != "" {
		ep, err := splitAddress(cfg.Address)
		if err != nil {
			return nil, err
		}
		return []srvdns.Endpoint{ep}, nil
	}
	if cfg.resolve != nil {
		return cfg.resolve(ctx, cfg.SRVName, cfg.SRVHost)
	}
	return srvdns.Lookup(ctx, cfg.SRVName, cfg.SRVHost, cfg.dnsServer)
}

func splitAddress(addr string) (srvdns.Endpoint, error) {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil || host == "" {
		return srvdns.Endpoint{}, errors.New("address must be host:port")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return srvdns.Endpoint{}, errors.New("invalid port in address")
	}
	return srvdns.Endpoint{Host: host, Port: uint16(port)}, nil
}

// GetStableToken asks the VPS for the cached stable token.
func (c *Client) GetStableToken(ctx context.Context) (string, int32, error) {
	var out *pb.GetStableTokenResponse
	err := c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		resp, err := api.GetStableToken(ctx, &pb.GetStableTokenRequest{})
		out = resp
		return err
	})
	if err != nil {
		return "", 0, err
	}
	return out.GetAccessToken(), out.GetExpiresIn(), nil
}

// UploadImage uploads one jpeg or png through the VPS.
func (c *Client) UploadImage(ctx context.Context, content []byte, filename string) (string, error) {
	var out *pb.UploadImageResponse
	err := c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		resp, err := api.UploadImage(ctx, &pb.UploadImageRequest{Content: content, Filename: filename})
		out = resp
		return err
	})
	if err != nil {
		return "", err
	}
	return out.GetUrl(), nil
}

// UploadCover uploads a jpeg or png and returns the permanent media_id.
func (c *Client) UploadCover(ctx context.Context, content []byte, filename string) (string, error) {
	var out *pb.UploadCoverResponse
	err := c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		resp, err := api.UploadCover(ctx, &pb.UploadCoverRequest{Content: content, Filename: filename})
		out = resp
		return err
	})
	if err != nil {
		return "", err
	}
	return out.GetMediaId(), nil
}

// AddDraft creates a draft and returns its media_id.
func (c *Client) AddDraft(ctx context.Context, articles []*pb.Article) (string, error) {
	var out *pb.AddDraftResponse
	err := c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		resp, err := api.AddDraft(ctx, &pb.AddDraftRequest{Articles: articles})
		out = resp
		return err
	})
	if err != nil {
		return "", err
	}
	return out.GetMediaId(), nil
}

// UpdateDraft replaces one article in an existing draft.
func (c *Client) UpdateDraft(ctx context.Context, mediaID string, index uint32, article *pb.Article) error {
	return c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		_, err := api.UpdateDraft(ctx, &pb.UpdateDraftRequest{
			MediaId: mediaID,
			Index:   index,
			Article: article,
		})
		return err
	})
}

// ListDrafts returns news items already in the draft box.
func (c *Client) ListDrafts(ctx context.Context) ([]*pb.ListedArticle, error) {
	var out *pb.ListDraftsResponse
	err := c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		resp, err := api.ListDrafts(ctx, &pb.ListDraftsRequest{})
		out = resp
		return err
	})
	if err != nil {
		return nil, err
	}
	return out.GetArticles(), nil
}

// ListPublished returns news items already published. It does not publish.
func (c *Client) ListPublished(ctx context.Context) ([]*pb.ListedArticle, error) {
	var out *pb.ListPublishedResponse
	err := c.call(ctx, func(ctx context.Context, api pb.WechatEgressClient) error {
		resp, err := api.ListPublished(ctx, &pb.ListPublishedRequest{})
		out = resp
		return err
	})
	if err != nil {
		return nil, err
	}
	return out.GetArticles(), nil
}

func (c *Client) call(ctx context.Context, fn func(context.Context, pb.WechatEgressClient) error) error {
	if len(c.endpoints) == 0 {
		return errors.New("no SRV targets")
	}
	var failures []error
	for i, ep := range c.endpoints {
		err := c.callOne(ctx, ep, fn)
		if err == nil {
			return nil
		}
		if status.Code(err) == codes.Unavailable && i != len(c.endpoints)-1 {
			failures = append(failures, fmt.Errorf("%s: %w", ep.Address(), err))
			continue
		}
		if len(failures) == 0 {
			return err
		}
		failures = append(failures, fmt.Errorf("%s: %w", ep.Address(), err))
		return errors.Join(failures...)
	}
	return errors.Join(failures...)
}

func (c *Client) callOne(ctx context.Context, ep srvdns.Endpoint, fn func(context.Context, pb.WechatEgressClient) error) error {
	creds, err := c.transport(ep.Host)
	if err != nil {
		return err
	}
	addr := ep.Address()
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(creds),
		grpc.WithPerRPCCredentials(bearerCreds{token: c.token, allowInsecure: c.insecure}),
		grpc.WithDisableServiceConfig(),
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(maxMessage),
			grpc.MaxCallSendMsgSize(maxMessage),
		),
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			var d net.Dialer
			return d.DialContext(dialCtx, "tcp", addr)
		}),
	)
	if err != nil {
		return status.Errorf(codes.Unavailable, "dial %s: %v", addr, err)
	}
	defer conn.Close()
	return fn(ctx, pb.NewWechatEgressClient(conn))
}

func (c *Client) transport(host string) (credentials.TransportCredentials, error) {
	if c.insecure {
		if !isLoopbackHost(host) {
			return nil, errors.New("insecure gRPC is only allowed for loopback targets")
		}
		return insecure.NewCredentials(), nil
	}
	tlsCfg, err := c.tlsConfig(host)
	if err != nil {
		return nil, err
	}
	return credentials.NewTLS(tlsCfg), nil
}

func (c *Client) tlsConfig(host string) (*tls.Config, error) {
	tlsCfg := &tls.Config{
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: false,
	}
	if c.serverName != "" {
		tlsCfg.ServerName = c.serverName
	} else {
		tlsCfg.ServerName = host
	}
	if c.caFile != "" {
		pem, err := os.ReadFile(c.caFile)
		if err != nil {
			return nil, fmt.Errorf("read TLS CA: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("no certificates in %s", c.caFile)
		}
		tlsCfg.RootCAs = pool
	}
	return tlsCfg, nil
}

type bearerCreds struct {
	token         string
	allowInsecure bool
}

func (b bearerCreds) GetRequestMetadata(context.Context, ...string) (map[string]string, error) {
	return map[string]string{"authorization": "Bearer " + b.token}, nil
}

func (b bearerCreds) RequireTransportSecurity() bool { return !b.allowInsecure }
