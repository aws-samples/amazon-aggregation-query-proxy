# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

#!/bin/sh

CUR_DIR=$(pwd)
cd ..
echo "Building the maven package"
mvn install
cp ./query-app/target/query-app-1.0-SNAPSHOT.jar ./build-script/query-app-1.0-SNAPSHOT.jar
cp ./query-app/conf/keyspaces-aggregation-query-proxy.yaml ./build-script/keyspaces-aggregation-query-proxy.yaml
cp ./query-app/conf/KeyspacesConnector.conf ./build-script/KeyspacesConnector.conf

cd ${CUR_DIR}
echo "Building the docker image"
docker build -t simple-aggregation-query-app .

ACCOUNT_ID=$1
REGION=$2

echo "Logging ECR and uploading the image"
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com

docker tag simple-aggregation-query-app:latest $ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/simple-aggregation-query-app:latest
docker push $ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/simple-aggregation-query-app

echo "Completed uploading the data to ECR"