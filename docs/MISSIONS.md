# Mission content version 2

All 60 missions are executable JSON (`app/src/main/assets/missions/aws-001.json` … `aws-060.json`), generated deterministically by `tools/generate_content.py` + `tools/chapter_content.py`. Each has zh/ja/en title, story, lesson and three-level hints, initial resources whose fault is a real resource misconfiguration, typed objectives/constraints, an allowlist of operations and a reference solution used only by tests.

Unlock is sequential: mission N requires mission N−1 completed. A chapter district on the map opens when the last mission of the previous chapter is complete.

| ID | Ch | Title | Services | Objective kinds (typed) | Ref steps |
|---|---|---|---|---|---|
| aws-001 | 01 | First cloud base | IAM | attribute, region | 4 |
| aws-002 | 01 | Cloud operator | IAM | allowed, exists | 2 |
| aws-003 | 01 | Excessive permissions | IAM | leastPrivilege | 1 |
| aws-004 | 01 | Server identity | IAM | roleAccess | 4 |
| aws-005 | 01 | Identity crisis | IAM | attribute | 1 |
| aws-006 | 01 | Least privilege | IAM | allowed, denied, leastPrivilege | 1 |
| aws-007 | 02 | First EC2 | EC2, IAM, CloudWatch | healthy | 1 |
| aws-008 | 02 | Stopped web server | EC2, IAM, CloudWatch | healthy | 1 |
| aws-009 | 02 | Disk capacity crisis | EC2, IAM, CloudWatch | attribute, diskFree | 2 |
| aws-010 | 02 | Server image | EC2, IAM, CloudWatch | attribute, healthy | 2 |
| aws-011 | 02 | Expensive server | EC2, IAM, CloudWatch | healthy, hourlyBudget | 3 |
| aws-012 | 02 | Midnight outage | EC2, IAM, CloudWatch | healthy | 1 |
| aws-013 | 03 | VPC and subnets | VPC, EC2 | attribute, exists | 3 |
| aws-014 | 03 | Gateway and routing | VPC, EC2 | network | 2 |
| aws-015 | 03 | Website outage | VPC, EC2 | network | 3 |
| aws-016 | 03 | Protect private database | VPC, EC2 | attribute, database, networkBlocked, noOpenIngress | 3 |
| aws-017 | 03 | Private egress | VPC, EC2 | network, networkBlocked | 2 |
| aws-018 | 03 | Network labyrinth | VPC, EC2 | database, network | 3 |
| aws-019 | 04 | First cloud object | S3, EFS, EBS | objectContent, objectReadable | 2 |
| aws-020 | 04 | Recover deleted map | S3, EFS, EBS | objectContent | 1 |
| aws-021 | 04 | Close public data exposure | S3, EFS, EBS | bucketPrivate, objectReadable | 1 |
| aws-022 | 04 | Archive aged data | S3, EFS, EBS | objectStorage | 2 |
| aws-023 | 04 | Shared filesystem | S3, EFS, EBS | efsMounted | 4 |
| aws-024 | 04 | Data protection perimeter | S3, EFS, EBS | attribute, backup, objectContent | 4 |
| aws-025 | 05 | Hospital database launch | RDS, DynamoDB, VPC | attribute, database | 1 |
| aws-026 | 05 | Database connection refused | RDS, DynamoDB, VPC | database, observed | 2 |
| aws-027 | 05 | Restore hospital backup | RDS, DynamoDB, VPC | attribute, database | 1 |
| aws-028 | 05 | Fail over across AZs | RDS, DynamoDB, VPC | attribute, database | 2 |
| aws-029 | 05 | Order partition key | RDS, DynamoDB, VPC | attribute, tableItem | 2 |
| aws-030 | 05 | Database incident drill | RDS, DynamoDB, VPC | attribute, database, observed, serviceReady | 4 |
| aws-031 | 06 | Traffic entry point | ALB, Auto Scaling, Route 53, CloudFront | serviceReady | 2 |
| aws-032 | 06 | Broken health check | ALB, Auto Scaling, Route 53, CloudFront | observed, serviceReady | 2 |
| aws-033 | 06 | Auto Scaling squad | ALB, Auto Scaling, Route 53, CloudFront | attribute, serviceReady | 1 |
| aws-034 | 06 | Name the city endpoint | ALB, Auto Scaling, Route 53, CloudFront | attribute, serviceReady | 1 |
| aws-035 | 06 | Private-origin distribution | ALB, Auto Scaling, Route 53, CloudFront | bucketPrivate, serviceReady | 1 |
| aws-036 | 06 | Traffic storm | ALB, Auto Scaling, Route 53, CloudFront | hourlyBudget, serviceReady | 2 |
| aws-037 | 07 | Read operational metrics | CloudWatch, SNS, CloudTrail | metricBelow, observed, serviceReady | 4 |
| aws-038 | 07 | Deliver the alarm | CloudWatch, SNS, CloudTrail | alarm, notified | 3 |
| aws-039 | 07 | Service exit clues | CloudWatch, SNS, CloudTrail | observed, serviceReady | 2 |
| aws-040 | 07 | Trace the privilege change | CloudWatch, SNS, CloudTrail | leastPrivilege, observed | 2 |
| aws-041 | 07 | On-call dashboard | CloudWatch, SNS, CloudTrail | dashboard | 1 |
| aws-042 | 07 | Midnight alarm storm | CloudWatch, SNS, CloudTrail | alarm, notified, serviceReady | 4 |
| aws-043 | 08 | First automation handler | Lambda, EventBridge, Systems Manager, CloudFormation | automationRan, exists, serviceReady | 2 |
| aws-044 | 08 | Virtual-clock scheduler | Lambda, EventBridge, Systems Manager, CloudFormation | atLeast, serviceReady | 2 |
| aws-045 | 08 | Operations document | Lambda, EventBridge, Systems Manager, CloudFormation | serviceReady | 1 |
| aws-046 | 08 | Fleet patch operation | Lambda, EventBridge, Systems Manager, CloudFormation | patched | 1 |
| aws-047 | 08 | Rebuild from a template | Lambda, EventBridge, Systems Manager, CloudFormation | attribute, exists, stack | 1 |
| aws-048 | 08 | Monitoring-driven recovery | Lambda, EventBridge, Systems Manager, CloudFormation | alarm, automationRan, notified, serviceReady | 2 |
| aws-049 | 09 | Container image registry | ECR, ECS, EKS | attribute | 2 |
| aws-050 | 09 | Run an ECS service | ECR, ECS, EKS | containerReady | 2 |
| aws-051 | 09 | Container will not start | ECR, ECS, EKS | containerReady, observed | 4 |
| aws-052 | 09 | Deploy an EKS application | ECR, ECS, EKS | containerReady, exists | 2 |
| aws-053 | 09 | CrashLoopBackOff | ECR, ECS, EKS | containerReady, observed | 2 |
| aws-054 | 09 | Restore container base | ECR, ECS, EKS | containerReady | 2 |
| aws-055 | 10 | Organization guardrail | Organizations, KMS, VPC, RDS, S3, CloudWatch | allowed, denied | 2 |
| aws-056 | 10 | Access encrypted data | Organizations, KMS, VPC, RDS, S3, CloudWatch | encrypted, objectReadable | 1 |
| aws-057 | 10 | Optimize cost while staying reliable | Organizations, KMS, VPC, RDS, S3, CloudWatch | attribute, database, hourlyBudget, serviceReady | 6 |
| aws-058 | 10 | Cross-region disaster recovery | Organizations, KMS, VPC, RDS, S3, CloudWatch | database, objectContent, replica | 4 |
| aws-059 | 10 | Architecture review | Organizations, KMS, VPC, RDS, S3, CloudWatch | architecture, observed | 4 |
| aws-060 | 10 | Shadow Fault final battle | Organizations, KMS, VPC, RDS, S3, CloudWatch | alarm, architecture, bucketPrivate, containerReady, database, hourlyBudget, notified, objectReadable, serviceReady | 8 |

## Known simplifications

- Missions 1–12 keep the v0.1 simplified models (base markers for Region/AZ, a simulated MFA flag, one app-state fault in mission 12). Mission 1's world already starts in `ap-northeast-1`, so the region-select step is a no-op.
- Story text for chapters 3–10 uses one templated Nova briefing per mission; dialogue depth for Mira and Dr. Cloud is still thin.
- Investigation steps (e.g. `inspectLogs` in aws-042) are encouraged by hints but not required by validation in every mission; aws-057 accepts leaving a non-serving instance stopped as a valid cost path.
- Level-3 hints list the supported operation sequence; they do not include parameter values.
