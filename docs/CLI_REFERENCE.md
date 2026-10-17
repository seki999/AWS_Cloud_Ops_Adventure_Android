# CLI subset

In-app `aws help` is the authoritative syntax reference. Queries output JSON; describe-instances provides `Reservations[].Instances[].InstanceId`, `InstanceType` and `State.Name`. Mutation responses contain a Message. Simulator errors are structured JSON messages, not fake AWS responses.

Supported AWS-like commands:

```text
aws help
aws --version
aws configure list
aws ec2 describe-instances
aws ec2 run-instances --instance-id i-demo --instance-type t3.micro --image-id ami-base --subnet-id subnet-a --security-group-ids sg-web --availability-zone ap-northeast-1a
aws ec2 start-instances --instance-ids i-demo
aws ec2 stop-instances --instance-ids i-demo
aws ec2 reboot-instances --instance-ids i-demo
aws ec2 terminate-instances --instance-ids i-demo
aws ec2 describe-volumes
aws ec2 create-volume --volume-id vol-demo --size 20 --availability-zone ap-northeast-1a
aws ec2 attach-volume --volume-id vol-demo --instance-id i-demo
aws ec2 modify-volume --volume-id vol-demo --size 40
aws ec2 create-image --instance-id i-demo --name ami-demo
aws ec2 describe-vpcs
aws ec2 describe-subnets
aws ec2 describe-security-groups
aws iam list-users
aws iam create-user --user-name learner
aws iam list-roles
aws iam create-role --role-name web-role --assume-role-policy-document '{"Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam put-user-policy --user-name learner --policy-document '{"Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::rescue/*"}]}'
aws iam simulate-principal-policy --policy-source-arn learner --action-names s3:GetObject --resource-arns arn:aws:s3:::rescue/map
aws cloudwatch list-metrics
aws cloudwatch describe-alarms
```

The current world has no configured alarms; describe-alarms returns an empty array. Alarm creation/evaluation is planned. Only one resource ID per mutation is supported. Unknown options, missing arguments, unknown commands and malformed policies fail. No shell expansion, chaining, pipelines, file URLs, real configure credentials or real AWS calls.

Simulator extensions use `sim OP ID key=value ...`:

```text
sim create operator type=user
sim mfa admin-nova
sim trust web-role service=ec2.amazonaws.com
sim attachRole i-web role=web-role
sim growFilesystem vol-data
sim resizeInstance i-web instanceType=t3.micro
sim service i-web app=running
sim identity learner
sim identity commander
```

Console operation names are the same OP values. Policy editing is available as an action/resource form or AWS-like JSON through CLI. Quoted JSON strings are supported; advanced escaping and a general shell language are deliberately absent.
