#
# Build stage
#
FROM maven:3.6.0-jdk-11-slim AS build
COPY src /home/app/src
COPY pom.xml /home/app
RUN mvn -f /home/app/pom.xml clean package

#
# Package stage
#
FROM openjdk:11-jre-slim
COPY --from=build /home/app/target/duke-microservice-1.0-SNAPSHOT.jar /srv/
EXPOSE 4567
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /srv/duke-microservice-1.0-SNAPSHOT.jar"]


