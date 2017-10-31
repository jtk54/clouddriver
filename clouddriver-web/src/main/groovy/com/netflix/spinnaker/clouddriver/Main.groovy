/*
 * Copyright 2014-2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver

import com.google.api.client.http.HttpTransport
import com.netflix.spinnaker.clouddriver.security.config.SecurityConfig
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration
import org.springframework.boot.autoconfigure.groovy.template.GroovyTemplateAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.support.SpringBootServletInitializer
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import sun.net.InetAddressCachePolicy

import java.security.Security
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

@Configuration
@Import([
  WebConfig,
  SecurityConfig,
])
@ComponentScan([
  'com.netflix.spinnaker.config',
])
@EnableAutoConfiguration(exclude = [
    BatchAutoConfiguration,
    GroovyTemplateAutoConfiguration,
])
@EnableScheduling
class Main extends SpringBootServletInitializer {

  static final Map<String, String> DEFAULT_PROPS = [
    'netflix.environment'    : 'test',
    'netflix.account'        : '${netflix.environment}',
    'netflix.stack'          : 'test',
    'spring.config.location' : '${user.home}/.spinnaker/',
    'spring.application.name': 'clouddriver',
    'spring.config.name'     : 'spinnaker,${spring.application.name}',
    'spring.profiles.active' : '${netflix.environment},local'
  ]

  static {
    /**
     * We often operate in an environment where we expect resolution of DNS names for remote dependencies to change
     * frequently, so it's best to tell the JVM to avoid caching DNS results internally.
     */
    InetAddressCachePolicy.cachePolicy = InetAddressCachePolicy.NEVER
    Security.setProperty('networkaddress.cache.ttl', '0')
  }

  static void main(String... args) {
    launchArgs = args
    enableLogging() // Maybe this will work?
    new SpringApplicationBuilder().properties(DEFAULT_PROPS).sources(Main).run(args)
  }

  @Override
  SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    application.properties(DEFAULT_PROPS).sources(Main)
  }

  static String[] launchArgs = []

  static void enableLogging() {
    Logger logger = Logger.getLogger(HttpTransport.class.getName())
    logger.setLevel(Level.CONFIG)
    logger.addHandler(new Handler() {

      @Override
      void close() throws SecurityException {
      }

      @Override
      void flush() {
      }

      @Override
      void publish(LogRecord record) {
        // default ConsoleHandler will print = INFO to System.err
        if (record.getLevel().intValue() < Level.INFO.intValue()) {
          println(record.getMessage())
        }
      }
    })
  }
}

