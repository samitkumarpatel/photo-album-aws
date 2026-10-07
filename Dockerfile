# syntax=docker/dockerfile:1
# API and worker Lambda image. The Java Lambda runtime loads classes from ${LAMBDA_TASK_ROOT} and jars from its lib/,
# so this copies Maven's target/lambda/ layout rather than the Spring Boot fat jar (classes under BOOT-INF/).
#   docker buildx build --platform linux/arm64 --provenance=false -t <repo>:<tag> .
# The build stage runs on the build host's platform (Java bytecode is portable) and the final stage only copies files,
# so building for arm64 on an x86_64 machine, or the reverse, needs no emulation.

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
# Tests need Docker (LocalStack); run them with ./mvnw verify before building the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -Dmaven.test.skip=true \
 && mv target/lambda/lib target/lambda-lib

FROM public.ecr.aws/lambda/java:25
# Dependencies first: they change less often than the application, so their layer is usually reused on push.
COPY --from=build /workspace/target/lambda-lib ${LAMBDA_TASK_ROOT}/lib/
COPY --from=build /workspace/target/lambda/ ${LAMBDA_TASK_ROOT}/
# REST API behind the function URL. The worker function overrides this with
# net.samitkumar.photo_album_aws.processing.S3EventWorkerHandler::handleRequest.
CMD ["net.samitkumar.photo_album_aws.lambda.StreamLambdaHandler::handleRequest"]
