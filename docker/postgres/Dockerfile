# PostGIS 3.4 on PostgreSQL 16, plus pgvector for the 512-d appearance embeddings.
FROM postgis/postgis:16-3.4

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*
