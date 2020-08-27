FROM alpine:3.12.0

RUN apk add --update-cache \
    tree \
    curl \
  && rm -rf /var/cache/apk/*

RUN unzip bb.zip
CMD tail -f /dev/null