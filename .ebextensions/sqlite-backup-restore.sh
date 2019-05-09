#!/bin/bash

mkdir -p /data

if [ -f "/data/attestation.db" ]; then
    sqlite3 /data/attestation.db ".backup /tmp/bkp"
    aws s3 cp /tmp/bkp s3://STACK_NAME-attestation/attestation.db
    rm -rf /data/*
    cp -f /tmp/bkp /data/attestation.db
else
    aws s3 cp s3://STACK_NAME-attestation/attestation.db /data/ || true
fi