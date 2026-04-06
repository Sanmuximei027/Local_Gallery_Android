package com.example.localgallery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RuleEntity)

    @Query("DELETE FROM custom_rules WHERE imagePath = :imagePath")
    suspend fun deleteRule(imagePath: String)

    @Query("SELECT * FROM custom_rules WHERE imagePath = :imagePath")
    suspend fun getRuleByPath(imagePath: String): RuleEntity?

    @Query("SELECT * FROM custom_rules")
    fun getAllRulesFlow(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM custom_rules")
    suspend fun getAllRules(): List<RuleEntity>
}