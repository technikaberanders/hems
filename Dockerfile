FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
RUN apt-get update && \
    apt-get install -y wget && \
    wget https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.3.2/mariadb-java-client-3.3.2.jar -O /app/mariadb-java-client.jar && \
    apt-get clean
COPY src/ /app/src/
COPY run.sh /app/run.sh
RUN chmod +x /app/run.sh
RUN javac -cp /app/mariadb-java-client.jar /app/src/Main.java -d /app
CMD ["/app/run.sh"]
