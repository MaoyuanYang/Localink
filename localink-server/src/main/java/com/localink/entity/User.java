package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("lk_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String phone;

    private String password;

    private String nickName;

    private String icon;

    private String city;

    private String introduce;

    private Integer gender;

    private LocalDate birthday;

    private Integer level;

    private Integer fans;

    private Integer followee;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
