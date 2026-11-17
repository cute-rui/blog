// Package cli is the workflow entrypoint. The server subcommand runs on the
// VPS. The other subcommands run in CI and never see the WeChat AppSecret.
package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/lightwing/blog/wechat-egress/internal/config"
	"github.com/lightwing/blog/wechat-egress/internal/egress"
	"github.com/lightwing/blog/wechat-egress/internal/pb"
	"github.com/lightwing/blog/wechat-egress/internal/srvdns"
)

// Run executes one subcommand. getenv supplies environment variables.
func Run(args []string, getenv func(string) string, stdout, stderr io.Writer) int {
	if getenv == nil {
		getenv = os.Getenv
	}
	if len(args) == 0 || args[0] == "-h" || args[0] == "--help" || args[0] == "help" {
		fmt.Fprint(stderr, usage)
		if len(args) == 0 {
			return 2
		}
		return 0
	}
	switch args[0] {
	case "server":
		return runServer(args[1:], getenv, stderr)
	case "stable-token", "upload-image", "upload-cover", "add-draft", "update-draft":
		return runClient(args[0], args[1:], getenv, stdout, stderr)
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", args[0], usage)
		return 2
	}
}

const usage = `wechat-egress — fixed-IP front for WeChat draft APIs

Server (on the VPS):
  wechat-egress server --config /etc/wechat-egress/config.toml

The server reads AppID, AppSecret, the bearer token, listen address, and TLS
paths from that TOML file. WECHAT_EGRESS_CONFIG may point at the file.
Secrets are not read from the environment.

Client (in the workflow; picks one):
  wechat-egress stable-token
  wechat-egress upload-image --file image.png
  wechat-egress upload-cover --file cover.png
  wechat-egress add-draft --file draft.json
  wechat-egress update-draft --file draft.json

The client looks up SRV _wechat._tcp.$WECHAT_EGRESS_SRV_HOST unless --address
host:port is set. --insecure is plaintext and only dials a loopback address.

`

func runServer(args []string, getenv func(string) string, stderr io.Writer) int {
	fs := flag.NewFlagSet("server", flag.ContinueOnError)
	fs.SetOutput(stderr)
	configPath := fs.String("config", getenv("WECHAT_EGRESS_CONFIG"), "path to the TOML config")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}
	if fs.NArg() != 0 {
		fmt.Fprintln(stderr, "server does not take positional arguments")
		return 2
	}
	if strings.TrimSpace(*configPath) == "" {
		fmt.Fprintln(stderr, "server config is required: --config or WECHAT_EGRESS_CONFIG")
		return 2
	}
	cfg, err := config.Load(*configPath)
	if err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	srv, err := egress.NewServer(egress.ServerConfig{
		AppID:    cfg.AppID,
		Secret:   cfg.AppSecret,
		Token:    cfg.Token,
		APIBase:  cfg.APIBase,
		Insecure: cfg.Insecure,
		CertFile: cfg.TLSCert,
		KeyFile:  cfg.TLSKey,
		Logger:   log.New(stderr, "", 0),
	})
	if err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := srv.ListenAndServe(ctx, cfg.Listen); err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	return 0
}

func runClient(cmd string, args []string, getenv func(string) string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet(cmd, flag.ContinueOnError)
	fs.SetOutput(stderr)
	address := fs.String("address", getenv("WECHAT_EGRESS_ADDRESS"), "host:port, skips SRV")
	srvName := fs.String("srv-name", envOr(getenv, "WECHAT_EGRESS_SRV_NAME", srvdns.DefaultService), "SRV name, two labels")
	srvHost := fs.String("srv-host", getenv("WECHAT_EGRESS_SRV_HOST"), "domain appended to the SRV name")
	insecure := fs.Bool("insecure", envBool(getenv, "WECHAT_EGRESS_INSECURE"), "plaintext, loopback only")
	ca := fs.String("tls-ca", getenv("WECHAT_EGRESS_TLS_CA"), "PEM CA for the VPS certificate")
	serverName := fs.String("tls-server-name", getenv("WECHAT_EGRESS_TLS_SERVER_NAME"), "TLS server name")
	file := fs.String("file", "", "image file, or draft JSON")
	timeout := fs.Duration("timeout", 60*time.Second, "deadline for the call")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}
	if fs.NArg() != 0 {
		fmt.Fprintln(stderr, "unexpected positional arguments")
		return 2
	}
	if *address != "" && strings.TrimSpace(*srvHost) != "" {
		fmt.Fprintln(stderr, "using --address; SRV lookup skipped")
	}
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	client, err := egress.Dial(ctx, egress.ClientConfig{
		Token:      getenv("WECHAT_EGRESS_TOKEN"),
		Address:    *address,
		SRVName:    *srvName,
		SRVHost:    *srvHost,
		Insecure:   *insecure,
		CAFile:     *ca,
		ServerName: *serverName,
	})
	if err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	switch cmd {
	case "stable-token":
		token, expires, err := client.GetStableToken(ctx)
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		return writeJSON(stdout, stderr, map[string]any{"access_token": token, "expires_in": expires})
	case "upload-image":
		if *file == "" {
			fmt.Fprintln(stderr, "--file is required")
			return 2
		}
		content, err := readLimited(*file, 1<<20)
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		url, err := client.UploadImage(ctx, content, baseName(*file))
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		return writeJSON(stdout, stderr, map[string]any{"url": url})
	case "upload-cover":
		if *file == "" {
			fmt.Fprintln(stderr, "--file is required")
			return 2
		}
		content, err := readLimited(*file, 1<<20)
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		mediaID, err := client.UploadCover(ctx, content, baseName(*file))
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		return writeJSON(stdout, stderr, map[string]any{"media_id": mediaID})
	case "add-draft":
		articles, err := readAdd(*file)
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		mediaID, err := client.AddDraft(ctx, articles)
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		return writeJSON(stdout, stderr, map[string]any{"media_id": mediaID})
	case "update-draft":
		mediaID, index, article, err := readUpdate(*file)
		if err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		if err := client.UpdateDraft(ctx, mediaID, index, article); err != nil {
			fmt.Fprintln(stderr, err.Error())
			return 1
		}
		return writeJSON(stdout, stderr, map[string]any{"errcode": 0, "errmsg": "ok"})
	default:
		fmt.Fprintf(stderr, "unknown command %q\n", cmd)
		return 2
	}
}

func baseName(path string) string {
	slash := strings.LastIndexAny(path, `/\`)
	if slash >= 0 {
		return path[slash+1:]
	}
	return path
}

func readLimited(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if info.Size() > limit {
		return nil, fmt.Errorf("%s is larger than %d bytes", path, limit)
	}
	buf := make([]byte, info.Size())
	if _, err := io.ReadFull(f, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

type cropJSON struct {
	Ratio string `json:"ratio"`
	X1    string `json:"x1"`
	Y1    string `json:"y1"`
	X2    string `json:"x2"`
	Y2    string `json:"y2"`
}

type articleJSON struct {
	ArticleType        string `json:"article_type,omitempty"`
	Title              string `json:"title"`
	Author             string `json:"author,omitempty"`
	Digest             string `json:"digest,omitempty"`
	Content            string `json:"content"`
	ContentSourceURL   string `json:"content_source_url,omitempty"`
	ThumbMediaID       string `json:"thumb_media_id,omitempty"`
	NeedOpenComment    *int32 `json:"need_open_comment,omitempty"`
	OnlyFansCanComment *int32 `json:"only_fans_can_comment,omitempty"`
	CoverInfo          *struct {
		CropPercentList []cropJSON `json:"crop_percent_list"`
	} `json:"cover_info,omitempty"`
}

func readAdd(path string) ([]*pb.Article, error) {
	if path == "" {
		return nil, errors.New("--file is required")
	}
	var body struct {
		Articles []articleJSON `json:"articles"`
	}
	if err := decodeFile(path, &body); err != nil {
		return nil, err
	}
	out := make([]*pb.Article, 0, len(body.Articles))
	for _, item := range body.Articles {
		article, err := protoArticle(item)
		if err != nil {
			return nil, err
		}
		out = append(out, article)
	}
	return out, nil
}

func readUpdate(path string) (string, uint32, *pb.Article, error) {
	if path == "" {
		return "", 0, nil, errors.New("--file is required")
	}
	var body struct {
		MediaID  string      `json:"media_id"`
		Index    uint32      `json:"index"`
		Articles articleJSON `json:"articles"`
	}
	if err := decodeFile(path, &body); err != nil {
		return "", 0, nil, err
	}
	article, err := protoArticle(body.Articles)
	if err != nil {
		return "", 0, nil, err
	}
	return body.MediaID, body.Index, article, nil
}

func protoArticle(in articleJSON) (*pb.Article, error) {
	article := &pb.Article{
		ArticleType:        in.ArticleType,
		Title:              in.Title,
		Author:             in.Author,
		Digest:             in.Digest,
		Content:            in.Content,
		ContentSourceUrl:   in.ContentSourceURL,
		ThumbMediaId:       in.ThumbMediaID,
		NeedOpenComment:    in.NeedOpenComment,
		OnlyFansCanComment: in.OnlyFansCanComment,
	}
	if in.CoverInfo == nil {
		return article, nil
	}
	if len(in.CoverInfo.CropPercentList) == 0 {
		return nil, errors.New("cover_info needs crop_percent_list")
	}
	for _, crop := range in.CoverInfo.CropPercentList {
		article.CoverCrops = append(article.CoverCrops, &pb.CoverCrop{
			Ratio: crop.Ratio,
			X1:    crop.X1,
			Y1:    crop.Y1,
			X2:    crop.X2,
			Y2:    crop.Y2,
		})
	}
	return article, nil
}

func decodeFile(path string, dest any) error {
	raw, err := readLimited(path, 2<<20)
	if err != nil {
		return err
	}
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dest); err != nil {
		return err
	}
	if dec.More() {
		return errors.New("trailing JSON")
	}
	return nil
}

func writeJSON(stdout, stderr io.Writer, v any) int {
	enc := json.NewEncoder(stdout)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	return 0
}

func envOr(getenv func(string) string, key, fallback string) string {
	if v := getenv(key); strings.TrimSpace(v) != "" {
		return v
	}
	return fallback
}

func envBool(getenv func(string) string, key string) bool {
	v := strings.TrimSpace(getenv(key))
	return v == "1" || strings.EqualFold(v, "true")
}
