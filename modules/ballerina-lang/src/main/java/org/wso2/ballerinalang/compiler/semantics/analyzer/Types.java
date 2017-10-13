/*
*  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.wso2.ballerinalang.compiler.semantics.analyzer;

import org.ballerinalang.model.TreeBuilder;
import org.ballerinalang.model.types.ConstrainedType;
import org.ballerinalang.util.diagnostic.DiagnosticCode;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BCastOperatorSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BConversionOperatorSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.Symbols;
import org.wso2.ballerinalang.compiler.semantics.model.types.BAnyType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BArrayType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BBuiltInRefType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BConnectorType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BEnumType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BErrorType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BJSONType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BMapType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BStructType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BStructType.BStructField;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BTypeVisitor;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangExpression;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangTypeCastExpr;
import org.wso2.ballerinalang.compiler.util.CompilerContext;
import org.wso2.ballerinalang.compiler.util.Names;
import org.wso2.ballerinalang.compiler.util.TypeTags;
import org.wso2.ballerinalang.compiler.util.diagnotic.DiagnosticLog;
import org.wso2.ballerinalang.compiler.util.diagnotic.DiagnosticPos;
import org.wso2.ballerinalang.programfile.InstructionCodes;
import org.wso2.ballerinalang.util.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * @since 0.94
 */
public class Types {

    /**
     * @since 0.94
     */
    public enum RecordKind {
        STRUCT("struct"),
        MAP("map"),
        JSON("json");

        public String value;

        RecordKind(String value) {
            this.value = value;
        }
    }

    private static final CompilerContext.Key<Types> TYPES_KEY =
            new CompilerContext.Key<>();

    private Names names;
    private SymbolTable symTable;
    private SymbolResolver symResolver;
    private DiagnosticLog dlog;

    public static Types getInstance(CompilerContext context) {
        Types types = context.get(TYPES_KEY);
        if (types == null) {
            types = new Types(context);
        }

        return types;
    }

    public Types(CompilerContext context) {
        context.put(TYPES_KEY, this);

        this.names = Names.getInstance(context);
        this.symTable = SymbolTable.getInstance(context);
        this.symResolver = SymbolResolver.getInstance(context);
        this.dlog = DiagnosticLog.getInstance(context);
    }

    public List<BType> checkTypes(BLangExpression node, List<BType> actualTypes, List<BType> expTypes) {
        List<BType> resTypes = new ArrayList<>();
        for (int i = 0; i < actualTypes.size(); i++) {
            resTypes.add(checkType(node, actualTypes.get(i), expTypes.get(i)));
        }
        return resTypes;
    }

    public BType checkType(BLangExpression node, BType actualType, BType expType) {
        return checkType(node, actualType, expType, DiagnosticCode.INCOMPATIBLE_TYPES);
    }

    public BType checkType(BLangExpression expr, BType actualType, BType expType, DiagnosticCode diagCode) {
        expr.type = checkType(expr.pos, actualType, expType, diagCode);
        if (expr.type.tag == TypeTags.ERROR) {
            return expr.type;
        }

        // TODO Set an implicit cast expression if one available
        setImplicitCastExpr(expr, actualType, expType);

        return expr.type;
    }

    public BType checkType(DiagnosticPos pos, BType actualType, BType expType, DiagnosticCode diagCode) {
        // First check whether both references points to the same object.
        if (actualType == expType) {
            return actualType;
        } else if (expType.tag == TypeTags.ERROR) {
            return expType;
        } else if (expType.tag == TypeTags.NONE) {
            return actualType;
        } else if (actualType.tag == TypeTags.ERROR) {
            return actualType;
        } else if (isAssignable(actualType, expType)) {
            return actualType;
        } else if (isImplicitCastPossible(actualType, expType)) {
            return actualType;
        }


        // e.g. incompatible types: expected 'int', found 'string'
        dlog.error(pos, diagCode, expType, actualType);
        return symTable.errType;
    }

    public boolean isValueType(BType type) {
        return type.tag <= TypeTags.TYPE;
    }

    public boolean isAnnotationFieldType(BType type) {
        return this.isValueType(type) ||
                (type.tag == TypeTags.ANNOTATION) ||
                (type.tag == TypeTags.ARRAY && ((BArrayType) type).eType.tag == TypeTags.ANNOTATION) ||
                (type.tag == TypeTags.ARRAY && isValueType(((BArrayType) type).eType));
    }

    public boolean isAssignable(BType actualType, BType expType) {
        // First check whether both references points to the same object.
        if (actualType == expType) {
            return true;
        }

        if (expType.tag == TypeTags.ERROR) {
            return true;
        }

        // If the actual type or the rhs type is NULL, then the expected type cannot be a value type.
        if (actualType.tag == TypeTags.NULL && !isValueType(expType)) {
            return true;
        }

        // If the both type tags are equal, then perform following checks.
        if (actualType.tag == expType.tag && isValueType(actualType)) {
            return true;
        } else if (actualType.tag == expType.tag && actualType.tag == TypeTags.INVOKABLE) {
            return checkFunctionTypeEquivalent(actualType, expType);
        } else if (actualType.tag == expType.tag &&
                !isUserDefinedType(actualType) && !isConstrainedType(actualType) &&
                actualType.tag != TypeTags.ANNOTATION) {
            return true;
        } else if (actualType.tag == expType.tag && actualType.tag == TypeTags.ARRAY) {
            return checkArrayEquivalent(actualType, expType);
        }

        // If both types are constrained types, then check whether they are assignable
        if (isConstrainedTypeAssignable(actualType, expType)) {
            return true;
        }

        // TODO Check connector equivalency
        // TODO Check enums
        return false;
    }

    public boolean isUserDefinedType(BType type) {
        return type.tag == TypeTags.STRUCT || type.tag == TypeTags.CONNECTOR ||
                type.tag == TypeTags.ENUM || type.tag == TypeTags.ARRAY;
    }

    public boolean isConstrainedType(BType type) {
        return type.tag == TypeTags.JSON;
    }

    /**
     * Checks for the availability of an implicit cast from the actual type to the expected type.
     * 1) Lookup the symbol table for an implicit cast
     * 2) Check whether the expected type is ANY
     * 3) If both types are array types, then check implicit cast possibility of their element types.
     *
     * @param actualType actual type of an expression.
     * @param expType    expected type from an expression.
     * @return true if there exist an implicit cast from the actual type to the expected type.
     */
    public boolean isImplicitCastPossible(BType actualType, BType expType) {
        if (isConstrainedType(expType) && ((ConstrainedType) expType).getConstraint() != symTable.noType) {
            return false;
        }

        BSymbol symbol = symResolver.resolveImplicitCastOperator(actualType, expType);
        if (symbol != symTable.notFoundSymbol) {
            return true;
        }

        if (expType.tag == TypeTags.ANY) {
            return true;
        }

        return actualType.tag == TypeTags.ARRAY && expType.tag == TypeTags.ARRAY &&
                isImplicitArrayCastPossible(actualType, expType);
    }

    public boolean isImplicitArrayCastPossible(BType actualType, BType expType) {
        if (expType.tag == TypeTags.ARRAY && actualType.tag == TypeTags.ARRAY) {
            // Both types are array types
            BArrayType lhrArrayType = (BArrayType) expType;
            BArrayType rhsArrayType = (BArrayType) actualType;
            return isImplicitArrayCastPossible(lhrArrayType.getElementType(), rhsArrayType.getElementType());

        } else if (actualType.tag == TypeTags.ARRAY) {
            // Only the right-hand side is an array type
            // Then lhs type should 'any' type
            return expType.tag == TypeTags.ANY;

        } else if (expType.tag == TypeTags.ARRAY) {
            // Only the left-hand side is an array type
            return false;
        }

        // Now both types are not array types and they have to be equal
        if (expType == actualType) {
            // TODO Figure out this.
            return true;
        }

        // In this case, lhs type should be of type 'any' and the rhs type cannot be a value type
        return expType.tag == TypeTags.ANY && !isValueType(actualType);
    }

    public boolean checkFunctionTypeEquivalent(BType actualType, BType expType) {
        BInvokableType aType = (BInvokableType) actualType;
        BInvokableType eType = (BInvokableType) expType;
        if (aType.paramTypes.size() != eType.paramTypes.size() || aType.retTypes.size() != eType.retTypes.size()) {
            return false;
        }
        if (!aType.paramTypes.equals(eType.paramTypes)) {
            return false;
        }
        return aType.retTypes.equals(eType.retTypes);
    }

    public boolean checkArrayEquivalent(BType actualType, BType expType) {
        if (expType.tag == TypeTags.ARRAY && actualType.tag == TypeTags.ARRAY) {
            // Both types are array types
            BArrayType lhrArrayType = (BArrayType) expType;
            BArrayType rhsArrayType = (BArrayType) actualType;
            return checkArrayEquivalent(lhrArrayType.getElementType(), rhsArrayType.getElementType());

        }
        // Now one or both types are not array types and they have to be equal
        if (expType == actualType) {
            return true;
        }
        return false;
    }

    public boolean checkStructEquivalency(BType actualType, BType expType) {
        if (actualType.tag != TypeTags.STRUCT || expType.tag != TypeTags.STRUCT) {
            return false;
        }

        BStructType expStructType = (BStructType) expType;
        BStructType actualStructType = (BStructType) actualType;
        if (expStructType.fields.size() > actualStructType.fields.size()) {
            return false;
        }

        for (int i = 0; i < expStructType.fields.size(); i++) {
            BStructField expStructField = expStructType.fields.get(i);
            BStructField actualStructField = actualStructType.fields.get(i);
            if (expStructField.name.equals(actualStructField.name) &&
                    isAssignable(actualStructField.type, expStructField.type)) {
                continue;
            }
            return false;
        }

        return true;
    }

    public void setImplicitCastExpr(BLangExpression expr, BType actualType, BType expType) {
        BSymbol symbol = symResolver.resolveImplicitCastOperator(actualType, expType);
        if (symbol == symTable.notFoundSymbol) {
            return;
        }

        BCastOperatorSymbol castSymbol = (BCastOperatorSymbol) symbol;

        BLangTypeCastExpr implicitCastExpr = (BLangTypeCastExpr) TreeBuilder.createTypeCastNode();
        implicitCastExpr.pos = expr.pos;
        implicitCastExpr.expr = expr.impCastExpr == null ? expr : expr.impCastExpr;
        implicitCastExpr.type = expType;
        implicitCastExpr.types = Lists.of(expType);
        implicitCastExpr.castSymbol = castSymbol;
        expr.impCastExpr = implicitCastExpr;
    }

    public BSymbol getCastOperator(BType sourceType, BType targetType) {
        if (sourceType == targetType) {
            return createCastOperatorSymbol(sourceType, targetType, true, InstructionCodes.NOP);
        }

        return targetType.accept(castVisitor, sourceType);
    }

    public BSymbol getConversionOperator(BType sourceType, BType targetType) {
        return targetType.accept(conversionVisitor, sourceType);
    }

    public BType getElementType(BType type) {
        if (type.tag != TypeTags.ARRAY) {
            return type;
        }

        return getElementType(((BArrayType) type).getElementType());
    }

    /**
     * Check whether a given struct can be used to constraint a JSON.
     * 
     * @param type struct type
     * @return flag indicating possibility of constraining
     */
    public boolean checkStructToJSONCompatibility(BType type) {
        if (type.tag != TypeTags.STRUCT) {
            return false;
        }

        List<BStructField> fields = ((BStructType) type).fields;
        for (int i = 0; i < fields.size(); i++) {
            BType fieldType = fields.get(i).type;
            if (checkStructFieldToJSONCompatibility(type, fieldType)) {
                continue;
            } else {
                return false;
            }
        }

        return true;
    }

    // private methods

    private boolean isConstrainedTypeAssignable(BType actualType, BType expType) {
        if (!isConstrainedType(actualType) || !isConstrainedType(expType)) {
            return false;
        }

        if (actualType.tag != expType.tag) {
            return false;
        }

        ConstrainedType expConstrainedType = (ConstrainedType) expType;
        ConstrainedType actualConstrainedType = (ConstrainedType) actualType;

        if (((BType) expConstrainedType.getConstraint()).tag == TypeTags.NONE) {
            return true;
        }

        if (expConstrainedType.getConstraint() == actualConstrainedType.getConstraint()) {
            return true;
        }

        return false;
    }

    private BCastOperatorSymbol createCastOperatorSymbol(BType sourceType,
                                                         BType targetType,
                                                         boolean safe,
                                                         int opcode) {
        return Symbols.createCastOperatorSymbol(sourceType, targetType, symTable.errTypeCastType,
                false, safe, opcode, null, null);
    }

    private BConversionOperatorSymbol createConversionOperatorSymbol(BType sourceType, 
                                                                     BType targetType, 
                                                                     boolean safe,
                                                                     int opcode) {
        return Symbols.createConversionOperatorSymbol(sourceType, targetType, symTable.errTypeConversionType, safe,
                opcode, null, null);
    }
    
    private BSymbol getExplicitArrayCastOperator(BType t, BType s, BType origT, BType origS) {
        if (t.tag == TypeTags.ARRAY && s.tag == TypeTags.ARRAY) {
            return getExplicitArrayCastOperator(((BArrayType) t).eType, ((BArrayType) s).eType, origT, origS);

        } else if (t.tag == TypeTags.ARRAY) {
            // If the target type is JSON array, and the source type is a JSON
            if (s.tag == TypeTags.JSON && getElementType(t).tag == TypeTags.JSON) {
                return createCastOperatorSymbol(origS, origT, false, InstructionCodes.CHECKCAST);
            }

            // If only the target type is an array type, then the source type must be of type 'any'
            if (s.tag == TypeTags.ANY) {
                return createCastOperatorSymbol(origS, origT, false, InstructionCodes.CHECKCAST);
            }
            return symTable.notFoundSymbol;

        } else if (s.tag == TypeTags.ARRAY) {
            if (t.tag == TypeTags.JSON) {
                return getExplicitArrayCastOperator(symTable.jsonType, ((BArrayType) s).eType, origT, origS);
            }

            // If only the source type is an array type, then the target type must be of type 'any'
            if (t.tag == TypeTags.ANY) {
                return createCastOperatorSymbol(origS, origT, true, InstructionCodes.NOP);
            }
            return symTable.notFoundSymbol;
        }

        // Now both types are not array types
        if (s == t) {
            return createCastOperatorSymbol(origS, origT, true, InstructionCodes.NOP);
        }

        // In this case, target type should be of type 'any' and the source type cannot be a value type
        if (t == symTable.anyType && !isValueType(s)) {
            return createCastOperatorSymbol(origS, origT, true, InstructionCodes.NOP);
        }

        if (!isValueType(t) && s == symTable.anyType) {
            return createCastOperatorSymbol(origS, origT, false, InstructionCodes.CHECKCAST);
        }
        return symTable.notFoundSymbol;
    }

    private boolean checkStructFieldToJSONCompatibility(BType structType, BType fieldType) {
        // If the struct field type is the struct
        if (structType == fieldType) {
            return true;
        }

        if (fieldType.tag == TypeTags.STRUCT) {
            return checkStructToJSONCompatibility(fieldType);
        }

        if (isAssignable(fieldType, symTable.jsonType) || isImplicitCastPossible(fieldType, symTable.jsonType)) {
            return true;
        }

        if (fieldType.tag == TypeTags.ARRAY) {
            return checkStructFieldToJSONCompatibility(structType, getElementType(fieldType));
        }

        return false;
    }

    /**
     * Check whether a given struct can be converted into a JSON
     * 
     * @param type struct type
     * @return flag indicating possibility of conversion
     */
    private boolean checkStructToJSONConvertibility(BType type) {
        if (type.tag != TypeTags.STRUCT) {
            return false;
        }

        List<BStructField> fields = ((BStructType) type).fields;
        for (int i = 0; i < fields.size(); i++) {
            BType fieldType = fields.get(i).type;
            if (checkStructFieldToJSONConvertibility(type, fieldType)) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    private boolean checkStructFieldToJSONConvertibility(BType structType, BType fieldType) {
        // If the struct field type is the struct
        if (structType == fieldType) {
            return true;
        }

        if (fieldType.tag == TypeTags.MAP || fieldType.tag == TypeTags.ANY) {
            return true;
        }

        if (fieldType.tag == TypeTags.STRUCT) {
            return checkStructToJSONConvertibility(fieldType);
        }
        
        if (fieldType.tag == TypeTags.ARRAY) {
            return checkStructFieldToJSONConvertibility(structType, getElementType(fieldType));
        }

        if (isAssignable(fieldType, symTable.jsonType) || isImplicitCastPossible(fieldType, symTable.jsonType)) {
            return true;
        }

        return false;
    }

    private BTypeVisitor<BSymbol> castVisitor = new BTypeVisitor<BSymbol>() {

        @Override
        public BSymbol visit(BType t, BType s) {
            return symResolver.resolveOperator(Names.CAST_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BBuiltInRefType t, BType s) {
            return symResolver.resolveOperator(Names.CAST_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BAnyType t, BType s) {
            if (isValueType(s)) {
                return symResolver.resolveOperator(Names.CAST_OP, Lists.of(s, t));
            }

            return createCastOperatorSymbol(s, t, true, InstructionCodes.NOP);
        }

        @Override
        public BSymbol visit(BMapType t, BType s) {
            return symResolver.resolveOperator(Names.CAST_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BJSONType t, BType s) {
            // Handle constrained JSON
            if (isConstrainedTypeAssignable(s, t)) {
                return createCastOperatorSymbol(s, t, true, InstructionCodes.NOP);
            } else if (s.tag == TypeTags.ARRAY) { 
                return getExplicitArrayCastOperator(t, s, t, s);
            } else if (t.constraint.tag != TypeTags.NONE) {
                return symTable.notFoundSymbol;
            }

            return symResolver.resolveOperator(Names.CAST_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BArrayType t, BType s) {
            return getExplicitArrayCastOperator(t, s, t, s);
        }

        @Override
        public BSymbol visit(BStructType t, BType s) {
            if (s == symTable.anyType) {
                return createCastOperatorSymbol(s, t, false, InstructionCodes.CHECKCAST);
            }

            if (s.tag == TypeTags.STRUCT && checkStructEquivalency(s, t)) {
                return createCastOperatorSymbol(s, t, true, InstructionCodes.NOP);
            } else if (s.tag == TypeTags.STRUCT || s.tag == TypeTags.ANY) {
                return createCastOperatorSymbol(s, t, false, InstructionCodes.CHECKCAST);
            }

            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BConnectorType t, BType s) {
            if (s == symTable.anyType) {
                return createCastOperatorSymbol(s, t, false, InstructionCodes.CHECKCAST);
            }

            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BEnumType t, BType s) {
            throw new AssertionError();
        }

        @Override
        public BSymbol visit(BErrorType t, BType s) {
            // TODO Implement. Not needed for now.
            throw new AssertionError();
        }
    };

    
    private BTypeVisitor<BSymbol> conversionVisitor = new BTypeVisitor<BSymbol>() {

        @Override
        public BSymbol visit(BType t, BType s) {
            return symResolver.resolveOperator(Names.CONVERSION_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BBuiltInRefType t, BType s) {
            return symResolver.resolveOperator(Names.CONVERSION_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BAnyType t, BType s) {
            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BMapType t, BType s) {
            if (s.tag == TypeTags.STRUCT) {
                return createConversionOperatorSymbol(s, t, true, InstructionCodes.T2MAP);
            }

            return symResolver.resolveOperator(Names.CONVERSION_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BJSONType t, BType s) {
            if (s.tag == TypeTags.STRUCT) {
                if (checkStructToJSONConvertibility(s)) {
                    return createConversionOperatorSymbol(s, t, false, InstructionCodes.T2JSON);
                } else {
                    return symTable.notFoundSymbol;
                }
            }

            return symResolver.resolveOperator(Names.CONVERSION_OP, Lists.of(s, t));
        }

        @Override
        public BSymbol visit(BArrayType t, BType s) {
            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BStructType t, BType s) {
            if (s.tag == TypeTags.MAP) {
                return createConversionOperatorSymbol(s, t, false, InstructionCodes.MAP2T);
            } else if (s.tag == TypeTags.JSON) {
                return createConversionOperatorSymbol(s, t, false, InstructionCodes.JSON2T);
            }

            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BConnectorType t, BType s) {
            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BEnumType t, BType s) {
            return symTable.notFoundSymbol;
        }

        @Override
        public BSymbol visit(BErrorType t, BType s) {
            return symTable.notFoundSymbol;
        }
    };
}