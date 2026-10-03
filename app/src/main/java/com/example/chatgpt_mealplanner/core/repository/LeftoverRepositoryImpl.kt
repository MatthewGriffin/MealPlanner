package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.Leftover
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeftoverRepositoryImpl @Inject constructor(
    private val leftoverDao: LeftoverDao
) : LeftoverRepository {

    override fun observeAvailableLeftovers(): Flow<List<Leftover>> {
        return leftoverDao.observeAvailableLeftovers().map { list ->
            list.map { it.toDomainModel() }
        }
    }

    override suspend fun getLeftoverById(id: String): Leftover? {
        return leftoverDao.getLeftoverById(id)?.toDomainModel()
    }

    override suspend fun insertLeftover(leftover: Leftover) {
        leftoverDao.insertLeftover(leftover.toEntity())
    }

    override suspend fun updateLeftover(leftover: Leftover) {
        leftoverDao.insertLeftover(leftover.toEntity())
    }

    override suspend fun deleteLeftover(id: String) {
        leftoverDao.deleteLeftover(id)
    }
}
