# faster-whisper behind an OpenAI-compatible API, using the pinned upstream Speaches image
# (https://github.com/speaches-ai/speaches). This thin layer only adds a first-start model download
# (Speaches 0.8.x has no preload option). VARIANT is "cpu" or "cuda".
ARG VARIANT=cpu
FROM ghcr.io/speaches-ai/speaches:0.8.3-${VARIANT}

USER root
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod 0755 /entrypoint.sh
USER ubuntu

# Never log request details (the default is "debug"); no UI; keep the model loaded.
ENV LOG_LEVEL=info ENABLE_UI=false WHISPER__TTL=-1
ENTRYPOINT ["/entrypoint.sh"]
