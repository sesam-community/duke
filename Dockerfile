FROM java:8-jre-alpine

ARG BuildNumber=unknown
LABEL BuildNumber $BuildNumber
ARG Commit=unknown
LABEL Commit $Commit

ADD target/duke-microservice-1.0-SNAPSHOT.jar /srv/

EXPOSE 4567
ENTRYPOINT ["java", "-jar", "/srv/duke-microservice-1.0-SNAPSHOT.jar"]


