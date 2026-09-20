package com.vowifi.sw.manager.data

internal class FakeMessageOwnerLabels {
    private val labels = linkedMapOf<SimOwnerId, MessageOwnerLabel>()

    fun load(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> = owners.associateWith {
        labels.getOrPut(it) { MessageOwnerLabel(labels.size.toLong() + 1, "") }
    }

    fun save(owner: SimOwnerId, remark: MessageOwnerRemark): MessageOwnerLabel {
        val saved = load(listOf(owner)).getValue(owner).copy(remark = remark.value)
        labels[owner] = saved
        return saved
    }
}
