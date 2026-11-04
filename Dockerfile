FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DATA_DIR=/data
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py search_sources.py streaming.py ./
COPY templates/ ./templates/
COPY static/ ./static/
RUN mkdir -p /data && chown -R 10001:10001 /data /app
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1
# StreamManager is in memory, so multiple gunicorn workers are not supported.
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--threads", "8", "--timeout", "75", "--graceful-timeout", "15", "app:app"]
