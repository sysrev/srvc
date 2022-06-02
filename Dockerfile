FROM babashka/babashka:latest
RUN apt-get install -y --no-install-recommends sqlite3

RUN mkdir srvc
WORKDIR /srvc/mvp-bb