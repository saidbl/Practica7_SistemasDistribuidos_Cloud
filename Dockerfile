FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY src ./src

RUN mkdir -p out && javac -d out $(find src -name "*.java")

CMD ["java", "-cp", "out", "lb.LoadBalancer"]