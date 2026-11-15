package egress

import (
	"github.com/lightwing/blog/wechat-egress/internal/pb"
	"github.com/lightwing/blog/wechat-egress/internal/wechat"
)

func articlesFromProto(in []*pb.Article) ([]wechat.Article, error) {
	out := make([]wechat.Article, 0, len(in))
	for _, item := range in {
		article, err := articleFromProto(item)
		if err != nil {
			return nil, err
		}
		out = append(out, article)
	}
	return out, nil
}

func articleFromProto(in *pb.Article) (wechat.Article, error) {
	if in == nil {
		return wechat.Article{}, &wechat.Invalid{Msg: "article is required"}
	}
	article := wechat.Article{
		ArticleType:      in.GetArticleType(),
		Title:            in.GetTitle(),
		Author:           in.GetAuthor(),
		Digest:           in.GetDigest(),
		Content:          in.GetContent(),
		ContentSourceURL: in.GetContentSourceUrl(),
		ThumbMediaID:     in.GetThumbMediaId(),
	}
	if in.NeedOpenComment != nil {
		value := int(*in.NeedOpenComment)
		article.NeedOpenComment = &value
	}
	if in.OnlyFansCanComment != nil {
		value := int(*in.OnlyFansCanComment)
		article.OnlyFansCanComment = &value
	}
	if crops := in.GetCoverCrops(); len(crops) > 0 {
		out := make([]wechat.Crop, 0, len(crops))
		for _, crop := range crops {
			if crop == nil {
				return wechat.Article{}, &wechat.Invalid{Msg: "cover crop is empty"}
			}
			out = append(out, wechat.Crop{
				Ratio: crop.GetRatio(),
				X1:    crop.GetX1(),
				Y1:    crop.GetY1(),
				X2:    crop.GetX2(),
				Y2:    crop.GetY2(),
			})
		}
		article = wechat.WithCrops(article, out)
	}
	return article, nil
}
