package main

import (
	"os"

	"github.com/lightwing/blog/wechat-egress/internal/cli"
)

func main() {
	os.Exit(cli.Run(os.Args[1:], nil, os.Stdout, os.Stderr))
}
