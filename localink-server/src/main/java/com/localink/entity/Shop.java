package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lk_shop")
public class Shop {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private Long typeId;

    private String images;

    private String area;

    private String address;

    private Double longitude;

    private Double latitude;

    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    private Integer score;

    private String openHours;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
