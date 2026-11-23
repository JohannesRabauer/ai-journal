# Piper TTS HTTP service. Versions are pinned; voices are downloaded on first start into the /voices volume.
FROM python:3.12.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN pip install piper-tts==1.8.0 flask==3.1.2 waitress==3.0.2 \
    && useradd --create-home --uid 1001 piper \
    && mkdir /voices && chown piper /voices
WORKDIR /app
COPY server.py entrypoint.sh /app/
USER 1001
ENV PIPER_VOICES_DIR=/voices HOME=/home/piper
VOLUME /voices
EXPOSE 5000
HEALTHCHECK --interval=15s --timeout=3s --start-period=120s --retries=5 \
  CMD python -c "import urllib.request as u; u.urlopen('http://127.0.0.1:5000/health', timeout=2)"
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
