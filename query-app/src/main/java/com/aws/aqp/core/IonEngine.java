// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.amazon.ion.IntegerSize;
import com.amazon.ion.IonDatagram;
import com.amazon.ion.IonInt;
import com.amazon.ion.IonSequence;
import com.amazon.ion.IonStruct;
import com.amazon.ion.IonSystem;
import com.amazon.ion.IonValue;
import com.amazon.ion.IonWriter;
import com.amazon.ion.system.IonSystemBuilder;
import com.amazon.ion.system.IonTextWriterBuilder;
import org.partiql.lang.CompilerPipeline;
import org.partiql.lang.eval.Bindings;
import org.partiql.lang.eval.EvaluationSession;
import org.partiql.lang.eval.ExprValue;
import org.partiql.lang.eval.ExprValueExtensionsKt;
import org.partiql.lang.eval.Expression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides intermediate JSON transformation via PartiQL
 */
public class IonEngine {

    /** Thread safe per ion-java, and comparatively expensive to build, so shared. */
    private static final IonSystem ION = IonSystemBuilder.standard().build();

    private final CompilerPipeline pipeline;

    public IonEngine() {
        // CompilerPipeline is the main entry point for the PartiQL lib giving you access to the
        // compiler
        this.pipeline = CompilerPipeline.standard();
    }

    /**
     * Evaluates {@code sql} with each top-level field of {@code document} bound as a global, so
     * {@code {"resultSet":[...]}} makes {@code FROM resultSet} range over the rows. Returns the
     * result collection as JSON.
     * <p>
     * The query used to be wrapped as {@code SELECT (<query>) AS resultSet FROM inputDocument}.
     * That relied on PartiQL 0.7 returning a whole collection from a SELECT-list subquery; newer
     * PartiQL applies SQL scalar-subquery coercion (one row, one column), so the wrapper fails.
     */
    public String query(String sql, String document) {
        Expression selectAndFilter = pipeline.compile(sql);
        IonDatagram values = ION.getLoader().load(document);
        if (values.size() != 1 || !(values.get(0) instanceof IonStruct)) {
            throw new IllegalArgumentException("Expected a single JSON object as the input document.");
        }

        promoteOutOfRangeIntegers(values.get(0));

        Map<String, ExprValue> globals = new HashMap<>();
        for (IonValue field : (IonStruct) values.get(0)) {
            globals.put(field.getFieldName(), ExprValue.Companion.of(field));
        }
        EvaluationSession session = EvaluationSession.builder()
                .globals(Bindings.Companion.ofMap(globals))
                .build();
        ExprValue selectAndFilterResult = selectAndFilter.eval(session);

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             IonWriter resultWriter = IonTextWriterBuilder.json().build(byteArrayOutputStream)) {
            ExprValueExtensionsKt.toIonValue(selectAndFilterResult, ION).writeTo(resultWriter);
            resultWriter.finish();
            return byteArrayOutputStream.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces every integer outside the 64-bit range with the equal Ion decimal, in place.
     * <p>
     * PartiQL 0.14 converts Ion integers to Java longs while binding its input, so larger values
     * wrapped silently: 12345678901234567890 became -6101065172474983726, even in a plain
     * projection. DynamoDB numbers carry up to 38 digits. Decimals are exact in PartiQL, so the
     * promoted value aggregates correctly. In-range integers are left alone so that integer
     * semantics, such as integer division, do not change.
     */
    private static void promoteOutOfRangeIntegers(IonValue value) {
        if (value instanceof IonStruct) {
            IonStruct struct = (IonStruct) value;
            List<String> promote = new ArrayList<>();
            for (IonValue field : struct) {
                if (isOutOfRangeInt(field)) {
                    promote.add(field.getFieldName());
                } else {
                    promoteOutOfRangeIntegers(field);
                }
            }
            for (String name : promote) {
                IonInt original = (IonInt) struct.get(name);
                struct.put(name, ION.newDecimal(new BigDecimal(original.bigIntegerValue())));
            }
        } else if (value instanceof IonSequence) {
            IonSequence sequence = (IonSequence) value;
            for (int i = 0; i < sequence.size(); i++) {
                IonValue element = sequence.get(i);
                if (isOutOfRangeInt(element)) {
                    sequence.set(i, ION.newDecimal(new BigDecimal(((IonInt) element).bigIntegerValue())));
                } else {
                    promoteOutOfRangeIntegers(element);
                }
            }
        }
    }

    private static boolean isOutOfRangeInt(IonValue value) {
        return value instanceof IonInt && !value.isNullValue()
                && ((IonInt) value).getIntegerSize() == IntegerSize.BIG_INTEGER;
    }
}
