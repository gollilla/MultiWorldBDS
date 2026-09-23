import { Stack, StackProps } from "aws-cdk-lib";
import { Vpc, SecurityGroup, Port, Peer } from "aws-cdk-lib/aws-ec2";
import { Construct } from "constructs";

export interface SecurityStackProps extends StackProps {
  vpc: Vpc;
}

export class SecurityStack extends Stack {
  public readonly waterdogSg: SecurityGroup;
  public readonly bdsSg: SecurityGroup;
  public readonly controlSg: SecurityGroup;

  constructor(scope: Construct, id: string, props: SecurityStackProps) {
    super(scope, id, props);

    this.waterdogSg = new SecurityGroup(this, "WaterdogSg", {
      vpc: props.vpc,
      description: "WaterdogPE proxy: public Bedrock listener + admin control API",
      allowAllOutbound: true,
    });
    this.waterdogSg.addIngressRule(
      Peer.anyIpv4(),
      Port.udp(19132),
      "Bedrock clients (RakNet)"
    );

    this.bdsSg = new SecurityGroup(this, "BdsSg", {
      vpc: props.vpc,
      description: "BDS world tasks: reachable only from the Waterdog proxy, never publicly",
      allowAllOutbound: true,
    });
    this.bdsSg.addIngressRule(
      this.waterdogSg,
      Port.udp(19132),
      "Waterdog downstream RakNet connection only"
    );

    // Attach this to whatever calls the WorldControl API (POST/DELETE /worlds) -
    // a bastion, a VPN client range, a CI runner, an automation Lambda, etc.
    // Nothing is attached to it yet; it exists so that grant can be scoped
    // precisely instead of opening :8081 to the BDS worlds or the internet.
    this.controlSg = new SecurityGroup(this, "ControlSg", {
      vpc: props.vpc,
      description: "Principals allowed to call the WorldControl HTTP API on the Waterdog task",
      allowAllOutbound: false,
    });
    this.waterdogSg.addIngressRule(
      this.controlSg,
      Port.tcp(8081),
      "WorldControl API (add/remove worlds) - not from the BDS worlds' SG or the internet"
    );
  }
}
