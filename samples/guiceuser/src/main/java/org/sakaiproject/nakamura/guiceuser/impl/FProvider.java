/**
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.guiceuser.impl;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.sakaiproject.nakamura.guiceuser.api.InterfaceE;
import org.sakaiproject.nakamura.guiceuser.api.InterfaceF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FProvider implements Provider<InterfaceF> {

  protected static final Logger LOGGER = LoggerFactory.getLogger(FProvider.class);
  private InterfaceE e;

  @Inject
  public FProvider(InterfaceE e)
  {
    this.e = e;
  }
  
  public InterfaceF get() {
    return new InterfaceF() 
    {

      public void printViaE() {
        LOGGER.info("Provided F printing via e via d");
        FProvider.this.getE().printHelloViaD();
      }
    };
  }

  protected InterfaceE getE() {
    return e;
  }

}
