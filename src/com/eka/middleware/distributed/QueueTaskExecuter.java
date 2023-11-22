package com.eka.middleware.distributed;

import java.util.Date;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.ignite.lang.IgniteBiPredicate;

import com.eka.middleware.distributed.offHeap.IgNode;
import com.eka.middleware.distributed.offHeap.IgQueue;
import com.eka.middleware.heap.CacheManager;
import com.eka.middleware.heap.HashMap;
import com.eka.middleware.server.ServiceManager;
import com.eka.middleware.service.DataPipeline;
import com.eka.middleware.service.RuntimePipeline;
import com.eka.middleware.service.ServiceUtils;
import com.eka.middleware.template.Tenant;

public class QueueTaskExecuter {

	private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(50);

	public static void start(final Tenant tenant) {

		IgNode.getIgnite().message().localListen(tenant.getName(), new IgniteBiPredicate<UUID, String>() {
			@Override
			public boolean apply(UUID nodeId, String data) {
				try {
					String json = data;
					Queue queue = QueueManager.getQueue(tenant, IgNode.getLocalNode(tenant));
					if (json != null)
						queue.add(json);
					return true; // Return true to keep listening
				} catch (Exception e) {
					ServiceUtils.printException("Could not add the message to node queue:"+IgNode.getLocalNode(tenant), e);
					return false;
				}
				
			}
		});

		executor.submit(() -> {
			String nodeID = IgNode.getRandomClusterNode(tenant);
			Queue queue = QueueManager.getQueue(tenant, "ServiceQueue");
			while (true) {
				try {
					String json = (String) ((IgQueue) queue).take();
					if (json != null && json.startsWith("INVOKE:")) {
						try {
							json = json.substring(7);
							execute(tenant, json, null);
							Thread.sleep((int)(IgNode.getIgnite().cluster().localNode().metrics().getAverageCpuLoad()*500d));
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				} catch (Throwable e) {
					ServiceUtils.printException(
							"Exception caused while reading tasks from ServiceQueue: " + nodeID,
							new Exception(e));
				}
			}
		});
		
		executor.submit(() -> {
			String nodeID = IgNode.getLocalNode(tenant);
			Queue queue = QueueManager.getQueue(tenant, nodeID);
			while (true) {
				try {
					String json = (String) ((IgQueue) queue).take();
					if (json != null && json.startsWith("INVOKE:")) {
						try {
							json = json.substring(7);
							execute(tenant, json, null);
							Thread.sleep((int)(IgNode.getIgnite().cluster().localNode().metrics().getAverageCpuLoad()*10d));
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				} catch (Throwable e) {
					ServiceUtils.printException(
							"Exception caused while reading tasks from internal node queue: " + nodeID,
							new Exception(e));
				}
			}
		});
		
		executor.submit(() -> {
			String nodeID = IgNode.getHighUsageNode(tenant);
			Queue queue = QueueManager.getQueue(tenant, nodeID);
			while (true) {
				try {
					Thread.sleep((int)(IgNode.getIgnite().cluster().localNode().metrics().getAverageCpuLoad()*50d));
					String json = (String) ((IgQueue) queue).take();
					if (json != null && json.startsWith("INVOKE:")) {
						try {
							json = json.substring(7);
							execute(tenant, json, null);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				} catch (Throwable e) {
					ServiceUtils.printException(
							"Exception caused while reading tasks from internal node queue: " + nodeID,
							new Exception(e));
				}
			}
		});
	}

	public static void start(final Tenant tenant, final String queueName, final String fqn) {
		executor.submit(() -> {
			while (true) {
				try {
					Queue queue = QueueManager.getQueue(tenant, queueName);
					String json = (String) queue.poll();
				} catch (Throwable e) {
					ServiceUtils.printException(
							"Exception caused while reading tasks from configured queue: " + queueName,
							new Exception(e));
				}
			}
		});
	}

	public static void execute(Tenant tenant, final String json, String fqn) {
		try {
			Map<String, Object> asyncInputDoc = ServiceUtils.jsonToMap(json);
			Map<String, Object> asyncOutputDoc = new HashMap<>();
			final Map<String, Object> metaData = (Map<String, Object>) asyncInputDoc.get("*metaData");
			if (fqn == null)
				fqn = (String) metaData.get("*fqnOfFunction");
			final String fqnOfFunction = fqn;
			final Boolean enableResponse = (Boolean) metaData.get("*enableResponse");
			final String correlationID = (String) metaData.get("*correlationID");
			final String uuidAsync = (String) metaData.get("*uuidAsync");
			final String batchId = (String) metaData.get("batchId");
			System.out.println("Batch subscribd BatchID: " + batchId);
			asyncOutputDoc.put("*metaData", metaData);
			//Queue bq = QueueManager.getQueue(tenant, "BatchQueue");
			//Map BSQC = CacheManager.getOrCreateNewCache(tenant, "BackupServiceQueueCache");
			executor.submit(() -> {

				RuntimePipeline rpRef = null;
				long startTime = System.currentTimeMillis();
				try {
					final RuntimePipeline rpAsync = RuntimePipeline.create(tenant, uuidAsync, correlationID, null,
							fqnOfFunction, "");
					rpRef = rpAsync;

					metaData.put("*start_time", new Date().toString());
					metaData.put("*start_time_ms", System.currentTimeMillis());
					final DataPipeline dpAsync = rpAsync.dataPipeLine;
					asyncInputDoc.forEach((k, v) -> {
						if (v != null && k != null)
							dpAsync.put(k, v);
					});

					dpAsync.appLog("TENANT", dpAsync.rp.getTenant().getName());
					dpAsync.appLog("URL_PATH", dpAsync.getUrlPath());
					dpAsync.appLog("RESOURCE_NAME", dpAsync.getCurrentResourceName());

					if (fqnOfFunction.startsWith("packages")) {
						metaData.put("*sessionID", dpAsync.getSessionId());
						try {
							ServiceManager.invokeJavaMethod(fqnOfFunction, dpAsync);
						} catch (Throwable e) {
							e.printStackTrace();
						}

					} else {
						ServiceUtils.executeEmbeddedService(dpAsync,
								CacheManager.getEmbeddedService(
										fqnOfFunction.replaceAll("embedded.", "").replaceAll(".main", ""),
										dpAsync.rp.getTenant()));
					}
					Map<String, Object> asyncOut = dpAsync.getMap();
					asyncOutputDoc.putAll(asyncOut); // put("asyncOutputDoc", asyncOut);
					metaData.put("status", "Completed");
				} catch (Exception e) {
					ServiceUtils.printException("Exception caused on async operation correlationID: " + correlationID
							+ ". Batch Id: " + metaData.get("batchId"), e);
					metaData.put("status", "Failed");
					metaData.put("error", e.getMessage());
					// throw e;
				} finally {
					metaData.put("*end_time", new Date().toString());
					metaData.put("*total_duration_ms", (System.currentTimeMillis() - startTime) + "");
					try {
						if (enableResponse) {
							String jsonResult = ServiceUtils.toJson(asyncOutputDoc);
							Map<String, Object> cache = CacheManager.getCacheAsMap(tenant);
							cache.put(batchId, jsonResult);
							// System.out.println(" - Batch result saved: " + batchId + "\n");
						}
						//BSQC.remove(batchId);
						//bq.remove(batchId);
						rpRef.destroy();
					} catch (Exception e) {
						ServiceUtils.printException(
								"Exception caused on async operation after successful execution. correlationID: "
										+ correlationID + ". Batch Id: " + metaData.get("batchId"),
								e);
					}
				}
			});
		} catch (Exception e) {
			ServiceUtils.printException("Exception in task execution. Task Data:" + json, e);
		}

	}

}
