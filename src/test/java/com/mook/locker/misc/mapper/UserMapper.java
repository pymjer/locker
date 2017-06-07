package com.mook.locker.misc.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.mook.locker.annotation.VersionLocker;
import com.mook.locker.misc.domain.User;

public interface UserMapper extends BaseMapper<User> {
	
	// 参数为POJO对象方式(推荐使用方式1)
	@VersionLocker(true)
	Integer updateUser(User user);

	// 通过plugin property配置来实现乐观锁
	Integer updateUserName( @Param("id") Integer id,@Param("name")String name,@Param("version") Long version);
	
	// 参数为单个参数方式(推荐使用方式2)
	@VersionLocker(true)
	Integer updateUser(@Param("name") String name, @Param("password") String password, @Param("version") Long version, @Param("id") Integer id);
	
	// 参数为Map方式(不推荐使用方式，不够直观)
	@VersionLocker(true)
	Integer updateUser(Map<Object, Object> user);
	
	// 单个参数未带@Param，报错(严重不推荐使用方式)
	@VersionLocker(true)
	Integer updateUserError(String name, String password, Long version, Integer id);
	
	// 不参与乐观锁控制
	Integer updateUserNoVersionLocker(User user);

	// 重置数据库数据
	void resetData(User user);
}
