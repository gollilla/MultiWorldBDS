import { Stack, StackProps } from "aws-cdk-lib";
import { Vpc, SubnetType, IpAddresses } from "aws-cdk-lib/aws-ec2";
import { Construct } from "constructs";

/**
 * Public-subnets-only VPC. No NAT Gateway: keeping this to what a
 * single-instance, no-load-balancer proxy actually needs keeps the
 * monthly cost close to zero. Fargate tasks (both Waterdog and each BDS
 * world) get a public IP so they can still reach the internet (BDS
 * downloads its own binary and Waterdog's world control pulls itself in
 * this repo's build) - security groups, not subnet placement, are what
 * keep the BDS worlds from being reachable directly.
 */
export class NetworkStack extends Stack {
  public readonly vpc: Vpc;

  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    this.vpc = new Vpc(this, "Vpc", {
      ipAddresses: IpAddresses.cidr("10.42.0.0/16"),
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: "public",
          subnetType: SubnetType.PUBLIC,
          cidrMask: 24,
        },
      ],
    });
  }
}
