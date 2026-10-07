FROM public.ecr.aws/lambda/java:25

WORKDIR ${LAMBDA_TASK_ROOT}
COPY target/photo-album-aws-0.0.1-SNAPSHOT.jar app.jar
RUN jar -xf app.jar && rm app.jar

CMD ["net.samitkumar.photo_album_aws.lambda.StreamLambdaHandler::handleRequest"]