#!/bin/sh
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0
#
# Builds the image and pushes it to ECR.
#   build-script/build.sh <AWS_ACCOUNT> <REGION>
#
# The Maven build runs inside the Docker build (see Dockerfile), so no local Maven or JDK is
# needed and nothing is copied into build-script/.

set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <AWS_ACCOUNT> <REGION>" >&2
  exit 2
fi
ACCOUNT_ID=$1
REGION=$2

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
IMAGE=simple-aggregation-query-app
REGISTRY="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"
# Immutable, traceable tag alongside :latest, so a deployment can name exactly what it runs.
VERSION=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)

echo "Building the docker image $IMAGE:$VERSION"
docker build -f "$REPO_ROOT/build-script/Dockerfile" -t "$IMAGE:$VERSION" -t "$IMAGE:latest" "$REPO_ROOT"

echo "Logging in to ECR and uploading the image"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

for tag in "$VERSION" latest; do
  docker tag "$IMAGE:$tag" "$REGISTRY/$IMAGE:$tag"
  docker push "$REGISTRY/$IMAGE:$tag"
done

echo "Pushed $REGISTRY/$IMAGE:$VERSION"
