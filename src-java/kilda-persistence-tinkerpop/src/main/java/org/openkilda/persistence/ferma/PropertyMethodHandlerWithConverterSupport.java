/* Copyright 2020 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.persistence.ferma;

import org.openkilda.persistence.ferma.frames.converters.AttributeConverter;
import org.openkilda.persistence.ferma.frames.converters.Convert;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.syncleus.ferma.ElementFrame;
import com.syncleus.ferma.annotations.Property;
import com.syncleus.ferma.framefactories.annotation.CachesReflection;
import com.syncleus.ferma.framefactories.annotation.PropertyMethodHandler;
import com.syncleus.ferma.framefactories.annotation.ReflectionUtility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * A method handler that implemented support of the Property and Convert annotations.
 */
final class PropertyMethodHandlerWithConverterSupport extends PropertyMethodHandler {

    @Override
    public <E> DynamicType.Builder<E> processMethod(DynamicType.Builder<E> builder, Method method,
                                                    Annotation annotation) {
        java.lang.reflect.Parameter[] arguments = method.getParameters();

        if (ReflectionUtility.isSetMethod(method)
                && arguments != null && arguments.length == 1) {
            return builder.method(ElementMatchers.is(method))
                    .intercept(MethodDelegation.to(SetPropertyInterceptor.class));

        } else if (ReflectionUtility.isGetMethod(method)
                && (arguments == null || arguments.length == 0)) {
            return builder.method(ElementMatchers.is(method))
                    .intercept(MethodDelegation.to(GetPropertyInterceptor.class));
        }

        return super.processMethod(builder, method, annotation);
    }

    /**
     * A method interceptor for getters.
     */
    public static final class GetPropertyInterceptor {
        /**
         * The interceptor implementation.
         */
        @RuntimeType
        public static Object getProperty(@This final ElementFrame thiz, @Origin final Method method) {
            final Property propertyAnnotation
                    = ((CachesReflection) thiz).getReflectionCache().getAnnotation(method, Property.class);
            final String value = propertyAnnotation.value();
            Object obj = thiz.getProperty(value);
            Convert convertAnnotation
                    = ((CachesReflection) thiz).getReflectionCache().getAnnotation(method, Convert.class);
            if (convertAnnotation != null) {
                AttributeConverter converter = converterCache.getUnchecked(convertAnnotation.value());
                obj = converter.toEntityAttribute(obj);
            }
            Class<?> returnType = method.getReturnType();
            // Some implementation doesn't support Integer as a property type
            if (returnType.isEnum()) {
                return getValueAsEnum(method, obj);
            } else {
                return obj;
            }
        }

        private static Enum getValueAsEnum(final Method method, final Object value) {
            final Class<Enum> en = (Class<Enum>) method.getReturnType();
            if (value != null) {
                return Enum.valueOf(en, value.toString());
            }

            return null;
        }
    }

    /**
     * A method interceptor for setters.
     */
    public static final class SetPropertyInterceptor {
        /**
         * The interceptor implementation.
         */
        @RuntimeType
        public static void setProperty(@This final ElementFrame thiz, @Origin final Method method,
                                       @RuntimeType @Argument(0) final Object obj) {
            final Property propertyAnnotation
                    = ((CachesReflection) thiz).getReflectionCache().getAnnotation(method, Property.class);
            final String value = propertyAnnotation.value();
            Object graphObj;
            Convert convertAnnotation
                    = ((CachesReflection) thiz).getReflectionCache().getAnnotation(method, Convert.class);
            if (convertAnnotation != null) {
                AttributeConverter converter = converterCache.getUnchecked(convertAnnotation.value());
                graphObj = converter.toGraphProperty(obj);
            } else {
                graphObj = obj;
            }
            if ((graphObj != null) && (graphObj.getClass().isEnum())) {
                thiz.setProperty(value, ((Enum<?>) graphObj).name());
            } else {
                thiz.setProperty(value, graphObj);
            }
        }
    }

    private static LoadingCache<Class<? extends AttributeConverter>, AttributeConverter> converterCache =
            CacheBuilder.newBuilder()
                    .build(new CacheLoader<Class<? extends AttributeConverter>, AttributeConverter>() {
                        public AttributeConverter load(Class<? extends AttributeConverter> converterType)
                                throws IllegalAccessException, InstantiationException {
                            return converterType.newInstance();
                        }
                    });
}
