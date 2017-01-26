/**
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
define(['lodash', 'log', 'd3', './ballerina-view', './variables-view', 'ballerina/ast/ballerina-ast-factory', './canvas',
            'typeMapper'], function (_, log, d3, BallerinaView, VariablesView, BallerinaASTFactory, Canvas, TypeMapper) {

    var TypeMapper = {};

    var placeHolderName = "data-mapper-container"
    var strokeColor = "#414e66";
    var strokeWidth = 2;
    var pointColor = "#414e66";
    var pointSize = 5;
    var dashStyle = "3 3";
    var idNameSeperator = "-";

    TypeMapper.init = function(onConnectionCallback, onDisconnectCallback) {
        TypeMapper.onConnection = onConnectionCallback;

        jsPlumb.Defaults.Container = $("#" + placeHolderName);
        jsPlumb.Defaults.PaintStyle = {
            strokeStyle:strokeColor,
            lineWidth:strokeWidth,
            dashstyle: dashStyle
        };

        jsPlumb.Defaults.EndpointStyle = {
            radius:pointSize,
            fillStyle:pointColor
        };
        jsPlumb.Defaults.Overlays = [
            [ "Arrow", {
                location:0.5,
                id:"arrow",
                length:14,
                foldback:0.8
            } ]
        ];

        jsPlumb.importDefaults({Connector : [ "Bezier", { curviness:1 } ]});
        jsPlumb.bind('dblclick', function (connection, e) {
            var PropertyConnection = {
                sourceStruct : connection.source.id.split(idNameSeperator)[0],
                sourceProperty : connection.source.id.split(idNameSeperator)[1],
                sourceType : connection.source.id.split(idNameSeperator)[2],
                targetStruct : connection.target.id.split(idNameSeperator)[0],
                targetProperty : connection.target.id.split(idNameSeperator)[1],
                targetType : connection.target.id.split(idNameSeperator)[2]
            }

            jsPlumb.detach(connection);
            onDisconnectCallback(PropertyConnection);
        });


    }

    TypeMapper.removeStruct  = function (name){
        jsPlumb.detachEveryConnection();
        $("#" + name).remove();
    }

    TypeMapper.addConnection  = function (connection) {
        jsPlumb.connect({
            source:connection.sourceStruct + idNameSeperator + connection.sourceProperty + idNameSeperator + connection.sourceType,
            target:connection.targetStruct + idNameSeperator + connection.targetProperty + idNameSeperator + connection.targetType
        });
    }

    TypeMapper.getConnections  = function () {
        var connections = [];

        for (var i = 0; i < jsPlumb.getConnections().length; i++) {
            var connection = {
                sourceStruct : jsPlumb.getConnections()[i].sourceId.split(idNameSeperator)[0],
                sourceProperty : jsPlumb.getConnections()[i].sourceId.split(idNameSeperator)[1],
                sourceType : jsPlumb.getConnections()[i].sourceId.split(idNameSeperator)[2],
                targetStruct : jsPlumb.getConnections()[i].targetId.split(idNameSeperator)[0],
                targetProperty : jsPlumb.getConnections()[i].targetId.split(idNameSeperator)[1],
                targetType : jsPlumb.getConnections()[i].targetId.split(idNameSeperator)[2]
            }
            connections.push(connection);
        };

        return connections;
    }

    TypeMapper.addSourceStruct  = function (struct) {
        TypeMapper.makeStruct(struct, 50, 50);
        for (var i = 0; i < struct.properties.length; i++) {
            TypeMapper.addSourceProperty($('#' + struct.name), struct.properties[i].name, struct.properties[i].type);
        };
    }

    TypeMapper.addTargetStruct  = function (struct) {
        var placeHolderWidth = document.getElementById(placeHolderName).offsetWidth;
        var posY = placeHolderWidth - (placeHolderWidth/4);
        TypeMapper.makeStruct(struct, 50, posY);
        for (var i = 0; i < struct.properties.length; i++) {
            TypeMapper.addTargetProperty($('#' +struct.name), struct.properties[i].name, struct.properties[i].type);
        };
    }

    TypeMapper.makeStruct  = function (struct, posX, posY) {
        var newStruct = $('<div>').attr('id', struct.name).addClass('struct');

        var structName = $('<div>').addClass('struct-name').text(struct.name);
        newStruct.append(structName);

        newStruct.css({
            'top': posX,
            'left': posY
        });

        $("#" + placeHolderName).append(newStruct);
        jsPlumb.draggable(newStruct, {
            containment: 'parent'
        });
    }

    TypeMapper.makeProperty  = function (parentId, name, type) {
        var id = parentId.selector.replace("#","") + idNameSeperator + name + idNameSeperator  + type;
        var property = $('<div>').attr('id', id).addClass('property')
        var propertyName = $('<span>').addClass('property-name').text(name);
        var seperator = $('<span>').addClass('property-name').text(":");
        var propertyType = $('<span>').addClass('property-type').text(type);

        property.append(propertyName);
        property.append(seperator);
        property.append(propertyType);
        $(parentId).append(property);

        return property;
    }

    TypeMapper.addSourceProperty  = function (parentId, name, type) {
        jsPlumb.makeSource(TypeMapper.makeProperty(parentId, name, type), {
            anchor:["Continuous", { faces:["right"] } ]
        });
    }

    TypeMapper.addTargetProperty  = function (parentId, name, type) {
        jsPlumb.makeTarget(TypeMapper.makeProperty(parentId, name, type), {
            maxConnections:1,
            anchor:["Continuous", { faces:[ "left"] } ],
            beforeDrop: function (params) {
                //Checks property types are equal
                var isValidTypes = params.sourceId.split(idNameSeperator)[2] == params.targetId.split(idNameSeperator)[2];

                if (isValidTypes) {

                    var connection = {
                        sourceStruct : params.sourceId.split(idNameSeperator)[0],
                        sourceProperty : params.sourceId.split(idNameSeperator)[1],
                        sourceType : params.sourceId.split(idNameSeperator)[2],
                        targetStruct : params.targetId.split(idNameSeperator)[0],
                        targetProperty : params.targetId.split(idNameSeperator)[1],
                        targetType : params.targetId.split(idNameSeperator)[2]
                    }

                    TypeMapper.onConnection(connection);
                }

                return isValidTypes;
            }
        });
    }

    return TypeStructDefinitionView;
});