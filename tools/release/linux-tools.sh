#!/usr/bin/env bash
set -euo pipefail

# GitHub's mirrorlist selected azure.archive.ubuntu.com and stalled on the
# 61 MB CJK font package (run 37541705756). Keep the fonts/tests, use Ubuntu's
# official HTTPS mirror, and bound stalled connections instead of waiting.
printf '%s\n' 'https://archive.ubuntu.com/ubuntu' |
    sudo tee /etc/apt/apt-mirrors.txt >/dev/null

network_options=(
    -o Acquire::Retries=2
    -o Acquire::http::Timeout=20
    -o Acquire::https::Timeout=20
)
timeout 180s sudo apt-get "${network_options[@]}" update -qq
timeout 300s sudo apt-get "${network_options[@]}" install -y \
    libfontconfig1 fonts-noto-cjk "$@"
