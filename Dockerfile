FROM openjdk:8-jdk-alpine

WORKDIR /app

COPY . .

RUN ./gradlew build -x test

ENTRYPOINT java -jar build/libs/gymbooker-1.0-SNAPSHOT.jar