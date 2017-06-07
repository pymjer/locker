package com.mook.locker.misc.mapper;

import org.apache.ibatis.annotations.Param;

public interface BaseMapper<T> {

	Integer update(@Param("t")T t);

}
