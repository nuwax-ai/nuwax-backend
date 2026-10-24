package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("space")
public class Space {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "_tenant_id")
    private Long tenantId;
    private String name;
    private String description;
    private String icon;
    private Type type;
    private Long creatorId;
    private Integer yn;
    private Date modified;
    private Date created;
    private Integer receivePublish;
    private Integer allowDevelop;
    public enum Type {
        Personal,
        Team,
        Class,
        /**
         * 群组空间：由内部服务间接口按租户创建（/api/internal/space/create），用于承载群聊/协作场景。
         * <p>
         * 数据库 space.type 是 enum 列，本值追加在枚举末尾（migration update-20260919-space-type-group.sql），
         * 存量行类型不受影响。页面端与平台空间管理后台列表按 Team 过滤，故群组空间目前不在这些入口展示。
         */
        Group,
        /**
         * 私聊空间（Direct Message）：与 Group 同管控——内部服务间接口可创建与删除，成员增删同样开放。
         * <p>
         * 与 Group 的唯一差别在创建入口：{@code /api/internal/space/create} 传 {@code type=DM} 时可带
         * 可选的 {@code peerUserId}，建空间时把「对方」一并写成成员（role=User）；Group 不接受该参数。
         * 成员数不设上限，与 Group 一致。
         * <p>
         * 数据库 space.type 是 enum 列，本值追加在枚举末尾（migration update-20260919-space-type-dm.sql），
         * 排在 Group 之后，故 Group 的序号也不变、存量行类型不受影响。
         */
        DM
    }

}
