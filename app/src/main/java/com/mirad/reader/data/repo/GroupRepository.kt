package com.mirad.reader.data.repo

import com.mirad.reader.data.db.dao.GroupDao
import com.mirad.reader.data.db.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

/** 书架分组仓库。 */
class GroupRepository(private val groupDao: GroupDao) {

    fun observeGroups(): Flow<List<GroupEntity>> = groupDao.observeAll()

    suspend fun groups(): List<GroupEntity> = groupDao.getAll()

    suspend fun create(name: String): Long {
        val order = groupDao.getAll().maxOfOrNull { it.sortOrder } ?: 0
        return groupDao.insert(GroupEntity(name = name, sortOrder = order + 1))
    }

    suspend fun rename(group: GroupEntity, name: String) = groupDao.update(group.copy(name = name))

    /** 删除分组并把组内书籍移回未分组。 */
    suspend fun delete(group: GroupEntity) {
        groupDao.clearBooksInGroup(group.id)
        groupDao.delete(group)
    }
}
