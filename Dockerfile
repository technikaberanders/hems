FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
RUN apt-get update && \
    apt-get install -y wget && \
    wget -O /app/mariadb-java-client.jar https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
COPY src/ /app/src/
COPY run.sh /app/run.sh
RUN chmod +x /app/run.sh
RUN javac -cp /app/mariadb-java-client.jar /app/src/Main.java -d /app
CMD ["/app/run.sh"]
