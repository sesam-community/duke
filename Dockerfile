FROM java:8-jre-alpine

ADD target/duke-microservice-1.0-SNAPSHOT.jar /srv/

RUN addgroup -S -g 1000 sesam && adduser -S -D -H -u 1000 -G sesam sesam
    
EXPOSE 4567
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /srv/duke-microservice-1.0-SNAPSHOT.jar"]


