# syntax=docker/dockerfile:1
# GraalVM native Lambda image for the API and SQS worker.
# Build on a machine whose architecture matches the Lambda target; native-image does not cross-compile.
# Example: docker buildx build --platform linux/arm64 --provenance=false -t <repo>:<tag> --push .

FROM --platform=$BUILDPLATFORM ghcr.io/graalvm/native-image-community:25 AS build
ARG TARGETARCH
ARG BUILDARCH
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN test -n "$TARGETARCH" && test -n "$BUILDARCH" && test "$TARGETARCH" = "$BUILDARCH" \
 || (echo "Set BUILDARCH to the native builder architecture; GraalVM native-image cannot cross-compile" >&2; exit 1)
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Pnative -DskipTests native:compile \
 && test -x target/photo-album-aws

FROM public.ecr.aws/lambda/provided:al2023
# The adapter implements Lambda's Runtime API for the native Spring HTTP server and SQS events.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.1.0 /lambda-adapter /opt/extensions/lambda-adapter
COPY --from=build /workspace/target/photo-album-aws /var/task/application

ENV PORT=8080 \
    AWS_LWA_READINESS_CHECK_PATH=/actuator/health \
    AWS_LWA_PASS_THROUGH_PATH=/events

# Lambda Web Adapter is an extension and must start the native web application as the runtime process.
ENTRYPOINT ["/var/task/application"]
CMD []
