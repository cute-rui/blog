package wechat

import (
	"bytes"
	"fmt"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	maxContentBytes = 1 << 20
	maxContentRunes = 20000
)

// Crop is one cover crop rectangle. Coordinates are WeChat's 0–1 strings.
type Crop struct {
	Ratio string `json:"ratio"`
	X1    string `json:"x1"`
	Y1    string `json:"y1"`
	X2    string `json:"x2"`
	Y2    string `json:"y2"`
}

// Article is one news draft. Comment pointers are omitted from JSON when nil
// and sent as 0 or 1 when set.
type Article struct {
	ArticleType        string `json:"article_type,omitempty"`
	Title              string `json:"title"`
	Author             string `json:"author,omitempty"`
	Digest             string `json:"digest,omitempty"`
	Content            string `json:"content"`
	ContentSourceURL   string `json:"content_source_url,omitempty"`
	ThumbMediaID       string `json:"thumb_media_id,omitempty"`
	NeedOpenComment    *int   `json:"need_open_comment,omitempty"`
	OnlyFansCanComment *int   `json:"only_fans_can_comment,omitempty"`
	CoverInfo          *struct {
		CropPercentList []Crop `json:"crop_percent_list"`
	} `json:"cover_info,omitempty"`
}

// CoverCrops returns the crop list, or nil when there is no cover crop.
func (a Article) CoverCrops() []Crop {
	if a.CoverInfo == nil {
		return nil
	}
	return a.CoverInfo.CropPercentList
}

// WithCrops returns a copy that includes crops. An empty list clears them.
func WithCrops(a Article, crops []Crop) Article {
	if len(crops) == 0 {
		a.CoverInfo = nil
		return a
	}
	a.CoverInfo = &struct {
		CropPercentList []Crop `json:"crop_percent_list"`
	}{CropPercentList: crops}
	return a
}

func validateArticles(articles []Article) error {
	if len(articles) == 0 {
		return &Invalid{Msg: "draft needs at least one article"}
	}
	if len(articles) > 8 {
		return &Invalid{Msg: "draft accepts at most 8 articles"}
	}
	for i, article := range articles {
		if err := validateArticle(article); err != nil {
			return fmt.Errorf("article %d: %w", i, err)
		}
	}
	return nil
}

func validateArticle(a Article) error {
	if a.ArticleType != "" && a.ArticleType != "news" {
		return &Invalid{Msg: "article_type must be news"}
	}
	if err := bounded("title", a.Title, 1, 32); err != nil {
		return err
	}
	if a.Author != "" {
		if err := bounded("author", a.Author, 1, 16); err != nil {
			return err
		}
	}
	if a.Digest != "" {
		if err := bounded("digest", a.Digest, 1, 120); err != nil {
			return err
		}
	}
	if err := bounded("content", a.Content, 1, maxContentRunes-1); err != nil {
		return err
	}
	if len(a.Content) >= maxContentBytes {
		return &Invalid{Msg: "content must be smaller than 1MB"}
	}
	if err := sourceURL(a.ContentSourceURL); err != nil {
		return err
	}
	if a.ThumbMediaID != "" {
		if err := validMediaID(a.ThumbMediaID); err != nil {
			return err
		}
	}
	if err := commentFlag("need_open_comment", a.NeedOpenComment); err != nil {
		return err
	}
	if err := commentFlag("only_fans_can_comment", a.OnlyFansCanComment); err != nil {
		return err
	}
	for _, crop := range a.CoverCrops() {
		if err := validateCrop(crop); err != nil {
			return err
		}
	}
	return nil
}

func bounded(field, value string, min, max int) error {
	if !utf8.ValidString(value) {
		return &Invalid{Msg: field + " must be UTF-8"}
	}
	n := utf8.RuneCountInString(value)
	if n < min || n > max {
		return &Invalid{Msg: fmt.Sprintf("%s must be %d to %d characters", field, min, max)}
	}
	return nil
}

func sourceURL(raw string) error {
	if raw == "" {
		return nil
	}
	if len(raw) > 1024 {
		return &Invalid{Msg: "content_source_url is too long"}
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil {
		return &Invalid{Msg: "content_source_url must be an http(s) URL"}
	}
	return nil
}

func commentFlag(name string, value *int) error {
	if value == nil {
		return nil
	}
	if *value != 0 && *value != 1 {
		return &Invalid{Msg: name + " must be 0 or 1"}
	}
	return nil
}

func validateCrop(c Crop) error {
	switch c.Ratio {
	case "2.35_1", "1_1":
	default:
		return &Invalid{Msg: "cover crop ratio must be 2.35_1 or 1_1"}
	}
	for _, pair := range []struct{ name, value string }{
		{"x1", c.X1}, {"y1", c.Y1}, {"x2", c.X2}, {"y2", c.Y2},
	} {
		n, err := strconv.ParseFloat(pair.value, 64)
		if err != nil || n < 0 || n > 1 {
			return &Invalid{Msg: "cover crop " + pair.name + " must be between 0 and 1"}
		}
	}
	return nil
}

func validMediaID(id string) error {
	if id == "" {
		return &Invalid{Msg: "media_id is required"}
	}
	if len(id) > 128 {
		return &Invalid{Msg: "media_id is too long"}
	}
	for _, c := range id {
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
			return &Invalid{Msg: "media_id has an invalid character"}
		}
	}
	return nil
}

func validateImage(content []byte, filename string) (contentType, safeName string, err error) {
	if len(content) == 0 {
		return "", "", &Invalid{Msg: "image is empty"}
	}
	if len(content) >= 1<<20 {
		return "", "", &Invalid{Msg: "image must be smaller than 1MB"}
	}
	switch {
	case bytes.HasPrefix(content, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}):
		contentType, safeName = "image/png", "image.png"
	case bytes.HasPrefix(content, []byte{0xff, 0xd8, 0xff}):
		contentType, safeName = "image/jpeg", "image.jpg"
	default:
		return "", "", &Invalid{Msg: "image must be jpeg or png"}
	}
	if filename == "" {
		return contentType, safeName, nil
	}
	if strings.ContainsAny(filename, `/\`) || filename != filepath.Base(filename) {
		return "", "", &Invalid{Msg: "filename must not contain a path"}
	}
	ext := strings.ToLower(filepath.Ext(filename))
	switch contentType {
	case "image/png":
		if ext != ".png" {
			return "", "", &Invalid{Msg: "filename extension does not match png"}
		}
	case "image/jpeg":
		if ext != ".jpg" && ext != ".jpeg" {
			return "", "", &Invalid{Msg: "filename extension does not match jpeg"}
		}
	default:
		return "", "", &Invalid{Msg: "image must be jpeg or png"}
	}
	return contentType, safeName, nil
}
