/**
 * The MIT License (MIT)
 * <p>
 * Copyright (c) 2016 342252328@qq.com
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mook.locker.interceptor;

import com.google.common.collect.Lists;
import com.mook.locker.annotation.VersionLocker;
import com.mook.locker.util.Constent;
import com.mook.locker.util.PluginUtil;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.LongTypeHandler;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * <p>MyBatis乐观锁插件<br>
 * <p>MyBatis Optimistic Locker Plugin<br>
 *
 * @author 342252328@qq.com
 * @date 2016-05-27
 * @version 1.0
 * @since JDK1.7
 *
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = ParameterHandler.class, method = "setParameters", args = {PreparedStatement.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class OptimisticLocker implements Interceptor {

    private static final Log log = LogFactory.getLog(OptimisticLocker.class);
    private Properties props = null;
    private List<String> points = null;
    String versionColumn = "version";
    String versionValueColumn = "t.version";

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object intercept(Invocation invocation) throws Exception {
        String tVersionColumn = "t.version";
        if (null != props && !props.isEmpty()) {
            versionColumn = props.getProperty("versionColumn", "version");
            versionValueColumn = props.getProperty("versionValueColumn", "t.version");
        }

        String interceptMethod = invocation.getMethod().getName();
        if ("prepare".equals(interceptMethod)) {

            StatementHandler routingHandler = (StatementHandler) PluginUtil.processTarget(invocation.getTarget());
            MetaObject routingMeta = SystemMetaObject.forObject(routingHandler);
            MetaObject hm = routingMeta.metaObjectForProperty("delegate");

            if (!isVersionLock(hm)) return invocation.proceed();

            String originalSql = (String) hm.getValue("boundSql.sql");
            StringBuilder builder = new StringBuilder(originalSql);
            builder.append(" AND ");
            builder.append(versionColumn);
            builder.append(" = ?");
            hm.setValue("boundSql.sql", builder.toString());

        } else if ("setParameters".equals(interceptMethod)) {

            ParameterHandler handler = (ParameterHandler) PluginUtil.processTarget(invocation.getTarget());
            MetaObject hm = SystemMetaObject.forObject(handler);

            if (!isVersionLock(hm)) return invocation.proceed();

            BoundSql boundSql = (BoundSql) hm.getValue("boundSql");
            Object parameterObject = boundSql.getParameterObject();

            Configuration configuration = ((MappedStatement) hm.getValue("mappedStatement")).getConfiguration();
            MetaObject pm = configuration.newMetaObject(parameterObject);


            List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
            List<String> parameterProps = Lists.transform(parameterMappings, (parameterMapping -> parameterMapping.getProperty()));

            String vColumn = parameterProps.contains(versionColumn) ? versionColumn : versionValueColumn;
            Object value = pm.getValue(vColumn);
            int versionLocation = parameterMappings.size() + 1;
            try {
                PreparedStatement ps = (PreparedStatement) invocation.getArgs()[0];
                TypeHandler typeHandler = new LongTypeHandler();
                typeHandler.setParameter(ps, versionLocation, value, JdbcType.BIGINT);
            } catch (TypeException | SQLException e) {
                throw new TypeException("set parameter 'version' faild, Cause: " + e, e);
            }

            if (value.getClass() != Long.class && value.getClass() != long.class) {
                if (log.isDebugEnabled()) {
                    log.error(Constent.LogPrefix + "property type error, the type of version property must be Long or long.");
                }
            }

            // increase version
            pm.setValue(vColumn, (long) value + 1);
        } else if ("update".equals(interceptMethod)) {
            Executor executor = (Executor) PluginUtil.processTarget(invocation.getTarget());
            MetaObject excutorMeta = SystemMetaObject.forObject(executor);
            MetaObject hm = excutorMeta.metaObjectForProperty("delegate");
            if (!isVersionLock(hm)) return invocation.proceed();

            int result = (int) invocation.proceed();
            if(result != 1) throw new RuntimeException("更新失败！");
        }
        return invocation.proceed();
    }

    /**
     * 是否不拦截
     * @param hm
     * @return
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     */
    private boolean isVersionLock(MetaObject hm) throws InvocationTargetException, IllegalAccessException {
        VersionLocker vl = VersionLockerResolver.resolve(hm);
        if (null != vl && vl.value()) {
            return true;
        }

        if (points == null) {
            points = new ArrayList<>();
            if (props != null && !props.isEmpty()) {
                String point = props.getProperty("point");
                if (point != null && !point.isEmpty()) {
                    points = Arrays.asList(point.split(","));
                }
            }
        }

        MappedStatement ms = (MappedStatement) hm.getValue("mappedStatement");
        String id = ms.getId();
        for (String point : points) {
            if (Pattern.matches(point, id)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler || target instanceof ParameterHandler || target instanceof Executor)
            return Plugin.wrap(target, this);
        return target;
    }

    @Override
    public void setProperties(Properties properties) {
        if (null != properties && !properties.isEmpty()) props = properties;
    }

}