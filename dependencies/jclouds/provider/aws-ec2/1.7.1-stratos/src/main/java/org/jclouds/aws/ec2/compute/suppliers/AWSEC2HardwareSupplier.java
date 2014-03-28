/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.aws.ec2.compute.suppliers;

import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.c1_medium;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.c1_xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.c3_2xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.c3_4xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.c3_8xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.c3_xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.cc1_4xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.cc2_8xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.cg1_4xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.g2_2xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.hi1_4xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.hs1_8xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.i2_2xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.i2_4xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.i2_8xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.i2_xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m1_large;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m1_medium;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m1_small;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m1_xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m2_2xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m2_4xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m2_xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m3_2xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m3_large;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m3_medium;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.m3_xlarge;
import static org.jclouds.ec2.compute.domain.EC2HardwareBuilder.t1_micro;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jclouds.aws.ec2.compute.config.ClusterCompute;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.ec2.compute.suppliers.EC2HardwareSupplier;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

/**
 * 
 * @author Adrian Cole
 */
@Singleton
public class AWSEC2HardwareSupplier extends EC2HardwareSupplier {

   private final Set<String> ccAmis;

   @Inject
   public AWSEC2HardwareSupplier(@ClusterCompute Set<String> ccAmis) {
      this.ccAmis = ccAmis;
   }

   @Override
   public Set<? extends Hardware> get() {
      Builder<Hardware> sizes = ImmutableSet.builder();
      sizes.add(cc1_4xlarge().supportsImageIds(ccAmis).build());
      sizes.add(cg1_4xlarge().supportsImageIds(ccAmis).build());
      sizes.add(cc2_8xlarge().supportsImageIds(ccAmis).build());
      sizes.add(hi1_4xlarge().supportsImageIds(ccAmis).build());
      sizes.add(hs1_8xlarge().supportsImageIds(ccAmis).build());
      sizes.add(g2_2xlarge().supportsImageIds(ccAmis).build());

      sizes.add(t1_micro().build());
      sizes.add(c1_medium().build());
      sizes.add(c1_xlarge().build());
      sizes.add(c3_xlarge().build());
      sizes.add(c3_2xlarge().build());
      sizes.add(c3_4xlarge().build());
      sizes.add(c3_8xlarge().build());
      sizes.add(i2_xlarge().build());
      sizes.add(i2_2xlarge().build());
      sizes.add(i2_4xlarge().build());
      sizes.add(i2_8xlarge().build());
      sizes.add(m1_large().build());
      sizes.add(m1_small().build());
      sizes.add(m1_medium().build());
      sizes.add(m1_xlarge().build());
      sizes.add(m2_xlarge().build());
      sizes.add(m2_2xlarge().build());
      sizes.add(m2_4xlarge().build());
      sizes.add(m3_medium().build());
      sizes.add(m3_large().build());
      sizes.add(m3_xlarge().build());
      sizes.add(m3_2xlarge().build());

      return sizes.build();
   }
}
