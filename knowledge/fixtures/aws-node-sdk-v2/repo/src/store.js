const AWS = require("aws-sdk");
const S3 = require("aws-sdk/clients/s3");

const s3 = new S3({ region: process.env.AWS_REGION });
const sqs = new AWS.SQS({ region: process.env.AWS_REGION });

async function putThumbnail(bucket, key, body) {
  return s3.putObject({ Bucket: bucket, Key: key, Body: body }).promise();
}

async function enqueue(queueUrl, message) {
  return sqs.sendMessage({ QueueUrl: queueUrl, MessageBody: message }).promise();
}

module.exports = { putThumbnail, enqueue };
