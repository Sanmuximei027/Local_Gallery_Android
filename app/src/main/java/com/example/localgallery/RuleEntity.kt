package com.example.localgallery

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_rules")
data class RuleEntity(
    @PrimaryKey
    val imagePath: String,
    val customAlbumName: String
)