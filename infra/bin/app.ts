#!/usr/bin/env node
import { App } from "aws-cdk-lib";
import { NetworkStack } from "../lib/network-stack";
import { SecurityStack } from "../lib/security-stack";
import { EcsStack } from "../lib/ecs-stack";

const app = new App();

const network = new NetworkStack(app, "BdsWaterdogpe-Network");

const security = new SecurityStack(app, "BdsWaterdogpe-Security", {
  vpc: network.vpc,
});

new EcsStack(app, "BdsWaterdogpe-Ecs", {
  vpc: network.vpc,
  waterdogSg: security.waterdogSg,
  bdsSg: security.bdsSg,
});
