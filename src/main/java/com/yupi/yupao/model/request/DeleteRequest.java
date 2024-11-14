package com.yupi.yupao.model.request;

import lombok.Data;

import java.io.Serializable;
@Data
public class DeleteRequest implements Serializable {
    private static final long serialVersionUID = 4206239569232832993L;
    private long id;
}
