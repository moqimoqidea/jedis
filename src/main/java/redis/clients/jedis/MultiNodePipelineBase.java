package redis.clients.jedis;

import java.io.Closeable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.commands.PipelineBinaryCommands;
import redis.clients.jedis.commands.PipelineCommands;
import redis.clients.jedis.commands.RedisModulePipelineCommands;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.graph.GraphCommandObjects;
import redis.clients.jedis.providers.ConnectionProvider;
import redis.clients.jedis.util.IOUtils;

public abstract class MultiNodePipelineBase extends PipelineBase
    implements PipelineCommands, PipelineBinaryCommands, RedisModulePipelineCommands, Closeable {

  private final Logger log = LoggerFactory.getLogger(getClass());

  /**
   * The number of processes for {@code sync()}. If you have enough cores for client (and you have
   * more than 3 cluster nodes), you may increase this number of workers.
   * Suggestion:&nbsp;&le;&nbsp;cluster&nbsp;nodes.
   */
  public static volatile int MULTI_NODE_PIPELINE_SYNC_WORKERS = 3;

  private final Map<HostAndPort, Queue<Response<?>>> pipelinedResponses;
  private final Map<HostAndPort, Connection> connections;
  private volatile boolean syncing = false;

  private final ExecutorService executorService = Executors.newFixedThreadPool(MULTI_NODE_PIPELINE_SYNC_WORKERS);

  public MultiNodePipelineBase(CommandObjects commandObjects) {
    super(commandObjects);
    pipelinedResponses = new LinkedHashMap<>();
    connections = new LinkedHashMap<>();
  }

  /**
   * Sub-classes must call this method, if graph commands are going to be used.
   * @param connectionProvider connection provider
   */
  protected final void prepareGraphCommands(ConnectionProvider connectionProvider) {
    GraphCommandObjects graphCommandObjects = new GraphCommandObjects(connectionProvider);
    graphCommandObjects.setBaseCommandArgumentsCreator((comm) -> this.commandObjects.commandArguments(comm));
    super.setGraphCommands(graphCommandObjects);
  }

  protected abstract HostAndPort getNodeKey(CommandArguments args);

  protected abstract Connection getConnection(HostAndPort nodeKey);

  @Override
  protected final <T> Response<T> appendCommand(CommandObject<T> commandObject) {
    HostAndPort nodeKey = getNodeKey(commandObject.getArguments());

    Queue<Response<?>> queue;
    Connection connection;
    if (pipelinedResponses.containsKey(nodeKey)) {
      queue = pipelinedResponses.get(nodeKey);
      connection = connections.get(nodeKey);
    } else {
      pipelinedResponses.putIfAbsent(nodeKey, new LinkedList<>());
      queue = pipelinedResponses.get(nodeKey);

      Connection newOne = getConnection(nodeKey);
      connections.putIfAbsent(nodeKey, newOne);
      connection = connections.get(nodeKey);
      if (connection != newOne) {
        log.debug("Duplicate connection to {}, closing it.", nodeKey);
        IOUtils.closeQuietly(newOne);
      }
    }

    connection.sendCommand(commandObject.getArguments());
    Response<T> response = new Response<>(commandObject.getBuilder());
    queue.add(response);
    return response;
  }

  @Override
  public void close() {
    try {
      sync();
    } finally {
      connections.values().forEach(IOUtils::closeQuietly);
    }
  }

  @Override
  public final void sync() {
    if (syncing) {
      return;
    }
    syncing = true;

    CountDownLatch countDownLatch = new CountDownLatch(pipelinedResponses.size());
    Iterator<Map.Entry<HostAndPort, Queue<Response<?>>>> pipelinedResponsesIterator
        = pipelinedResponses.entrySet().iterator();
    while (pipelinedResponsesIterator.hasNext()) {
      Map.Entry<HostAndPort, Queue<Response<?>>> entry = pipelinedResponsesIterator.next();
      HostAndPort nodeKey = entry.getKey();
      Queue<Response<?>> queue = entry.getValue();
      Connection connection = connections.get(nodeKey);
      executorService.submit(() -> {
        try {
          List<Object> unformatted = connection.getMany(queue.size());
          for (Object o : unformatted) {
            queue.poll().set(o);
          }
        } catch (JedisConnectionException jce) {
          log.error("Error with connection to " + nodeKey, jce);
          // cleanup the connection
          pipelinedResponsesIterator.remove();
          connections.remove(nodeKey);
          IOUtils.closeQuietly(connection);
        } finally {
          countDownLatch.countDown();
        }
      });
    }

    try {
      countDownLatch.await();
    } catch (InterruptedException e) {
      log.error("Thread is interrupted during sync.", e);
    }

    syncing = false;
  }

  @Deprecated
  public Response<Long> waitReplicas(int replicas, long timeout) {
    return appendCommand(commandObjects.waitReplicas(replicas, timeout));
  }
}
