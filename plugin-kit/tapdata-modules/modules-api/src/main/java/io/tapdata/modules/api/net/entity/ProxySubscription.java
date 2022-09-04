package io.tapdata.modules.api.net.entity;

import java.util.List;

public class ProxySubscription {
	private String nodeId;
	public ProxySubscription nodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	private List<String> subscribeIds;
	public ProxySubscription subscribeIds(List<String> subscribeIds) {
		this.subscribeIds = subscribeIds;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public void setNodeId(String nodeId) {
		this.nodeId = nodeId;
	}

	public List<String> getSubscribeIds() {
		return subscribeIds;
	}

	public void setSubscribeIds(List<String> subscribeIds) {
		this.subscribeIds = subscribeIds;
	}
}
