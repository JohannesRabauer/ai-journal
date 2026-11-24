FROM postgres:17.12

# Install pgvector (pinned release; the base image is pinned to a PostgreSQL minor version, update both on purpose)
RUN apt-get update && \
    apt-get install -y build-essential git postgresql-server-dev-$PG_MAJOR && \
    git clone --branch v0.8.7 --depth 1 https://github.com/pgvector/pgvector.git /pgvector && \
    cd /pgvector && \
    make && make install && \
    rm -rf /pgvector && \
    apt-get remove -y build-essential git postgresql-server-dev-$PG_MAJOR && \
    apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*
