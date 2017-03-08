FROM discoenv/clojure-base:master

ENV CONF_TEMPLATE=/usr/src/app/notificationagent.properties.tmpl
ENV CONF_FILENAME=notificationagent.properties
ENV PROGRAM=notification-agent

VOLUME ["/etc/iplant/de"]

COPY project.clj /usr/src/app/
RUN lein deps

COPY conf/main/logback.xml /usr/src/app/
COPY . /usr/src/app

RUN lein uberjar && \
    cp target/notification-agent-standalone.jar .

RUN ln -s "/usr/bin/java" "/bin/notification-agent"

ENTRYPOINT ["run-service", "-Dlogback.configurationFile=/etc/iplant/de/logging/notificationagent-logging.xml", "-cp", ".:notification-agent-standalone.jar", "notification_agent.core"]

ARG git_commit=unknown
ARG version=unknown
ARG descriptive_version=unknown

LABEL org.cyverse.git-ref="$git_commit"
LABEL org.cyverse.version="$version"
LABEL org.cyverse.descriptive-version="$descriptive_version"
LABEL org.label-schema.vcs-ref="$git_commit"
LABEL org.label-schema.vcs-url="https://github.com/cyverse-de/notification-agent"
LABEL org.label-schema.version="$descriptive_version"
