/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamsets.pipeline.stage.origin.pulsar;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.pulsar.config.PulsarErrors;
import com.streamsets.pipeline.lib.pulsar.config.PulsarGroups;
import com.streamsets.pipeline.stage.origin.lib.BasicConfig;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PulsarMessageConsumerImpl implements PulsarMessageConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(PulsarMessageConsumerImpl.class);
  private static final String PULSAR_SOURCE_CONFIG_TOPICS_PATTERN = "pulsarConfig.topicsPattern";
  private static final String PULSAR_SOURCE_CONFIG_TOPICS_LIST = "pulsarConfig.topicsList";
  private static final String PULSAR_SOURCE_CONFIG_DESTINATION_TOPIC = "pulsarConfig.originTopic";
  private static final int POLL_INTERVAL = 100; // ms

  private final BasicConfig basicConfig;
  private final PulsarSourceConfig pulsarConfig;
  private final PulsarMessageConverter pulsarMessageConverter;
  private PulsarClient pulsarClient;
  private Consumer messageConsumer;
  private Message lastSentButNotACKMessage; // used when multi topic = false
  private List<Message> sentButNotACKMessages; // used when multi topic = true


  public PulsarMessageConsumerImpl(
      BasicConfig basicConfig,
      PulsarSourceConfig pulsarSourceConfig,
      PulsarMessageConverter pulsarMessageConverter
  ) {
    this.basicConfig = basicConfig;
    this.pulsarConfig = pulsarSourceConfig;
    this.pulsarMessageConverter = pulsarMessageConverter;
  }

  public Message getLastSentButNotACKMessage() {
    return lastSentButNotACKMessage;
  }

  public List<Message> getSentButNotACKMessages() {
    return sentButNotACKMessages;
  }

  @Override
  public List<Stage.ConfigIssue> init(Source.Context context) {
    List<Stage.ConfigIssue> issues = new ArrayList<>();

    issues.addAll(pulsarConfig.init(context));

    if (issues.isEmpty()) {
      // pulsar client
      pulsarClient = pulsarConfig.getClient();

      // pulsar message consumer
      if (pulsarConfig.multiTopic) {
      /* Commented until Pulsar corrects the issue that now makes it impossible to use topics pattern (Problem related
      with comments in PulsarSourceConfig for usePatternForTopic variable. Currently only topics list option will be
      available until issue with topics pattern is fixed.
      if(pulsarConfig.usePatternForTopic) {
        try {
          messageConsumer = pulsarClient
              .newConsumer()
              .topicsPattern(pulsarConfig.topicsPattern)
              .subscriptionName(pulsarConfig.subscriptionName)
              .consumerName(pulsarConfig.consumerName)
              .subscribe();
        } catch (PulsarClientException e) {
          issues.add(context.createConfigIssue(
              PulsarGroups.PULSAR.name(),
              PULSAR_SOURCE_CONFIG_TOPICS_PATTERN,
              PulsarErrors.PULSAR_05,
              pulsarConfig.topicsPattern,
              pulsarConfig.subscriptionName,
              String.valueOf(e)
          ));
          LOG.info(Utils.format(PulsarErrors.PULSAR_05.getMessage(), pulsarConfig.topicsPattern,
              pulsarConfig.subscriptionName, String.valueOf(e)), e);
        }
      }
      else {
      */
        try {
          messageConsumer = pulsarClient.newConsumer()
                                        .topics(pulsarConfig.topicsList)
                                        .subscriptionName(pulsarConfig.subscriptionName)
                                        .consumerName(pulsarConfig.consumerName)
                                        .subscribe();
        } catch (PulsarClientException e) {
          issues.add(context.createConfigIssue(PulsarGroups.PULSAR.name(),
              PULSAR_SOURCE_CONFIG_TOPICS_LIST,
              PulsarErrors.PULSAR_06,
              pulsarConfig.subscriptionName,
              String.valueOf(e)
          ));
          LOG.info(Utils.format(PulsarErrors.PULSAR_06.getMessage(),
              pulsarConfig.subscriptionName,
              String.valueOf(e)
          ), e);
        }
//      }
      } else {
        try {
          messageConsumer = pulsarClient.newConsumer()
                                        .topic(pulsarConfig.originTopic)
                                        .subscriptionName(pulsarConfig.subscriptionName)
                                        .consumerName(pulsarConfig.consumerName)
                                        .subscribe();
        } catch (PulsarClientException e) {
          issues.add(context.createConfigIssue(PulsarGroups.PULSAR.name(),
              PULSAR_SOURCE_CONFIG_DESTINATION_TOPIC,
              PulsarErrors.PULSAR_10,
              pulsarConfig.originTopic,
              pulsarConfig.subscriptionName,
              String.valueOf(e)
          ));
          LOG.info(Utils.format(PulsarErrors.PULSAR_10.getMessage(),
              pulsarConfig.originTopic,
              pulsarConfig.subscriptionName,
              String.valueOf(e)
          ), e);
        }
      }
    }


    // initialize sentButNotACKMessages
    sentButNotACKMessages = new ArrayList<>();

    // initialize lastSentButNotACKMessage
    lastSentButNotACKMessage = null;

    return issues;
  }

  @Override
  public int take(BatchMaker batchMaker, Source.Context context, int batchSize) throws StageException {
    long start = System.currentTimeMillis();
    int numMessagesConsumed = 0;

    while (System.currentTimeMillis() - start < basicConfig.maxWaitTime && numMessagesConsumed < batchSize) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Attempting to take up to '{}' messages", (batchSize - numMessagesConsumed));
      }
      try {
        Message message;
        long pollInterval = basicConfig.maxWaitTime - (System.currentTimeMillis() - start);
        if (pollInterval > Integer.MAX_VALUE) {
          message = messageConsumer.receive(POLL_INTERVAL, TimeUnit.MILLISECONDS);
        } else {
          message = messageConsumer.receive(Long.valueOf(pollInterval).intValue(), TimeUnit.MILLISECONDS);
        }

        if (message != null) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("Got message: '{}'", new String(message.getData()));
          }
          String messageId = Base64.getEncoder().encodeToString(message.getMessageId().toByteArray());
          numMessagesConsumed += pulsarMessageConverter.convert(batchMaker, context, messageId, message);
          if (pulsarConfig.multiTopic) {
            sentButNotACKMessages.add(message);
          } else {
            lastSentButNotACKMessage = message;
          }
        }
      } catch (PulsarClientException e) {
        throw new StageException(PulsarErrors.PULSAR_08, e.toString(), e);
      }
    }

    return numMessagesConsumed;
  }

  @Override
  public void ack() throws StageException {
    if (pulsarConfig.multiTopic) {
      for (Message msg : sentButNotACKMessages) {
        try {
          messageConsumer.acknowledge(msg.getMessageId());
        } catch (PulsarClientException e) {
          throw new StageException(PulsarErrors.PULSAR_07,
              new String(msg.getMessageId().toByteArray()),
              e.toString(),
              e
          );
        }
      }
      sentButNotACKMessages.clear();
    } else {
      if (lastSentButNotACKMessage != null) {
        try {
          messageConsumer.acknowledgeCumulative(lastSentButNotACKMessage.getMessageId());
          lastSentButNotACKMessage = null;
        } catch (PulsarClientException e) {
          throw new StageException(PulsarErrors.PULSAR_07,
              new String(lastSentButNotACKMessage.getMessageId().toByteArray()),
              e.toString(),
              e
          );
        }
      }
    }
  }

  @Override
  public void close() {
    try {
      if (messageConsumer != null) {
        messageConsumer.close();
      }
    } catch (PulsarClientException e) {
      LOG.warn("Could not close consumer subscription {} for topic {}: {}",
          messageConsumer.getSubscription(),
          messageConsumer.getTopic(),
          e
      );
    }

    pulsarConfig.destroy();
  }

}
