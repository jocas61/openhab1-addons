/**
 * Copyright (c) 2010-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zwave.internal.protocol.commandclass;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openhab.binding.zwave.internal.config.ZWaveDbCommandClass;
import org.openhab.binding.zwave.internal.protocol.SerialMessage;
import org.openhab.binding.zwave.internal.protocol.SerialMessage.SerialMessageClass;
import org.openhab.binding.zwave.internal.protocol.SerialMessage.SerialMessagePriority;
import org.openhab.binding.zwave.internal.protocol.SerialMessage.SerialMessageType;
import org.openhab.binding.zwave.internal.protocol.ZWaveController;
import org.openhab.binding.zwave.internal.protocol.ZWaveEndpoint;
import org.openhab.binding.zwave.internal.protocol.ZWaveNode;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveCommandClassValueEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

/**
 * Handles the Meter command class. The Meter Command Class is intended for all
 * kinds of meters that report measured quantities, such as gas, electricity and
 * water meters
 *
 * @author Ben Jones
 * @author Jan-Willem Spuij
 * @author Chris Jackson
 * @since 1.4.0
 */
@XStreamAlias("meterCommandClass")
public class ZWaveMeterCommandClass extends ZWaveCommandClass
        implements ZWaveGetCommands, ZWaveCommandClassInitialization, ZWaveCommandClassDynamicState {

    @XStreamOmitField
    private static final Logger logger = LoggerFactory.getLogger(ZWaveMeterCommandClass.class);
    private static final int MAX_SUPPORTED_VERSION = 3;

    private static final int METER_GET = 0x01;
    private static final int METER_REPORT = 0x02;
    // Version 2 and 3
    private static final int METER_SUPPORTED_GET = 0x03;
    private static final int METER_SUPPORTED_REPORT = 0x04;
    private static final int METER_RESET = 0x05;

    private MeterType meterType = null;
    private final Set<MeterScale> meterScales = new HashSet<MeterScale>();

    private volatile boolean canReset = false;

    @XStreamOmitField
    private boolean initialiseDone = false;
    @XStreamOmitField
    private boolean dynamicDone = false;

    private boolean isGetSupported = true;
    private boolean isSupportRequestSupported = true;

    /**
     * Creates a new instance of the ZWaveMeterCommandClass class.
     *
     * @param node
     *            the node this command class belongs to
     * @param controller
     *            the controller to use
     * @param endpoint
     *            the endpoint this Command class belongs to
     */
    public ZWaveMeterCommandClass(ZWaveNode node, ZWaveController controller, ZWaveEndpoint endpoint) {
        super(node, controller, endpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandClass getCommandClass() {
        return CommandClass.METER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxVersion() {
        return MAX_SUPPORTED_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleApplicationCommandRequest(SerialMessage serialMessage, int offset, int endpoint) {
        logger.debug("NODE {}: Received Meter Request", this.getNode().getNodeId());
        int command = serialMessage.getMessagePayloadByte(offset);
        MeterScale scale;
        int meterTypeIndex;

        switch (command) {
            case METER_GET:
            case METER_SUPPORTED_GET:
            case METER_RESET:
                logger.warn("Command {} not implemented.", command);
                return;
            case METER_REPORT:
                logger.trace("NODE {}: Meter report received", this.getNode().getNodeId());

                if (serialMessage.getMessagePayload().length < offset + 3) {
                    logger.error("NODE {}: Meter Report: Buffer too short: length={}, required={}",
                            this.getNode().getNodeId(), serialMessage.getMessagePayload().length, offset + 3);
                    return;
                }

                meterTypeIndex = serialMessage.getMessagePayloadByte(offset + 1) & 0x1F;
                if (meterTypeIndex >= MeterType.values().length) {
                    logger.warn("NODE {}: Invalid meter type {}", this.getNode().getNodeId(), meterTypeIndex);
                    return;
                }

                meterType = MeterType.getMeterType(meterTypeIndex);

                int scaleIndex = (serialMessage.getMessagePayloadByte(offset + 2) & 0x18) >> 0x03;

                if (this.getVersion() > 2) {
                    // In version 3, an extra scale bit is stored in the meter type byte.
                    scaleIndex |= ((serialMessage.getMessagePayloadByte(offset + 1) & 0x80) >> 0x05);
                }

                scale = MeterScale.getMeterScale(meterType, scaleIndex);
                if (scale == null) {
                    logger.warn("NODE {}: Invalid meter scale {}", this.getNode().getNodeId(), scaleIndex);
                    return;
                }

                // add scale to the list of supported scales.
                if (!this.meterScales.contains(scale)) {
                    this.meterScales.add(scale);
                }

                try {
                    BigDecimal value = extractValue(serialMessage.getMessagePayload(), offset + 2);
                    logger.debug("NODE {}: Meter: Type={}({}), Scale={}({}), Value={}", this.getNode().getNodeId(),
                            meterType.getLabel(), meterTypeIndex, scale.getUnit(), scale.getScale(), value);

                    ZWaveMeterValueEvent zEvent = new ZWaveMeterValueEvent(this.getNode().getNodeId(), endpoint,
                            meterType, scale, value);
                    this.getController().notifyEventListeners(zEvent);
                } catch (NumberFormatException e) {
                    logger.error("NODE {}: Meter Value Error {}", this.getNode().getNodeId(), e);
                    return;
                }

                dynamicDone = true;
                break;
            case METER_SUPPORTED_REPORT:
                logger.trace("Process Meter Supported Report");

                canReset = (serialMessage.getMessagePayloadByte(offset + 1) & 0x80) != 0;
                meterTypeIndex = serialMessage.getMessagePayloadByte(offset + 1) & 0x1F;
                int supportedScales = serialMessage.getMessagePayloadByte(offset + 2);

                // only 4 scales are supported in version 2 of the command.
                if (this.getVersion() == 2) {
                    supportedScales &= 0x0F;
                }

                if (meterTypeIndex >= MeterType.values().length) {
                    logger.warn("NODE {}: Invalid meter type {}", this.getNode().getNodeId(), meterTypeIndex);
                    return;
                }

                meterType = MeterType.getMeterType(meterTypeIndex);
                logger.debug("NODE {}: Identified meter type {}({})", this.getNode().getNodeId(), meterType.getLabel(),
                        meterTypeIndex);

                for (int i = 0; i < 8; ++i) {
                    // scale is supported
                    if ((supportedScales & (1 << i)) == (1 << i)) {
                        scale = MeterScale.getMeterScale(meterType, i);

                        if (scale == null) {
                            logger.warn("NODE {}: Invalid meter scale {}", this.getNode().getNodeId(), i);
                            continue;
                        }

                        logger.debug("NODE {}: Meter Scale = {}({})", this.getNode().getNodeId(), scale.getUnit(),
                                scale.getScale());

                        // add scale to the list of supported scales.
                        if (!this.meterScales.contains(scale)) {
                            this.meterScales.add(scale);
                        }
                    }
                }

                initialiseDone = true;
                break;
            default:
                logger.warn(String.format("Unsupported Command 0x%02X for command class %s (0x%02X).", command,
                        this.getCommandClass().getLabel(), this.getCommandClass().getKey()));
        }
    }

    /**
     * Gets a SerialMessage with the METER_GET command
     *
     * @return the serial message
     */
    @Override
    public SerialMessage getValueMessage() {
        if (isGetSupported == false) {
            logger.debug("NODE {}: Node doesn't support get requests", this.getNode().getNodeId());
            return null;
        }

        logger.debug("NODE {}: Creating new message for application command METER_GET", this.getNode().getNodeId());
        SerialMessage result = new SerialMessage(this.getNode().getNodeId(), SerialMessageClass.SendData,
                SerialMessageType.Request, SerialMessageClass.ApplicationCommandHandler, SerialMessagePriority.Get);
        byte[] newPayload = { (byte) this.getNode().getNodeId(), 2, (byte) getCommandClass().getKey(),
                (byte) METER_GET };
        result.setMessagePayload(newPayload);
        return result;
    }

    @Override
    public boolean setOptions(ZWaveDbCommandClass options) {
        if (options.isGetSupported != null) {
            isGetSupported = options.isGetSupported;
        }

        if (options.meterCanReset != null) {
            canReset = options.meterCanReset;
        }

        if (options.meterType != null && options.meterScale != null) {
            meterType = MeterType.valueOf(options.meterType);
            logger.debug("NODE {}: Set meter type {}", this.getNode().getNodeId(), meterType.getLabel());

            List<String> scaleList = Arrays.asList(options.meterScale.split(","));
            for (String name : scaleList) {
                MeterScale scale = MeterScale.valueOf(name);
                if (scale == null) {
                    logger.warn("NODE {}: Invalid meter scale {}", this.getNode().getNodeId(), name);
                    continue;
                }

                logger.debug("NODE {}: Meter Scale {} = {}", this.getNode().getNodeId(), meterType.getLabel(),
                        scale.getUnit());

                // Add scale to the list of supported scales.
                if (!this.meterScales.contains(scale)) {
                    this.meterScales.add(scale);
                }
            }

            isSupportRequestSupported = false;
        }

        return true;
    }

    /**
     * Gets a SerialMessage with the METER_GET command
     *
     * @return the serial message
     */
    public SerialMessage getMessage(MeterScale meterScale) {
        logger.debug("NODE {}: Creating new message for application command METER_GET", this.getNode().getNodeId());
        SerialMessage result = new SerialMessage(this.getNode().getNodeId(), SerialMessageClass.SendData,
                SerialMessageType.Request, SerialMessageClass.ApplicationCommandHandler, SerialMessagePriority.Get);
        byte[] newPayload = { (byte) this.getNode().getNodeId(), 3, (byte) getCommandClass().getKey(), (byte) METER_GET,
                (byte) (meterScale.getScale() << 3) };
        result.setMessagePayload(newPayload);
        return result;
    }

    /**
     * Gets a SerialMessage with the METER_SUPPORTED_GET command
     *
     * @return the serial message, or null if the supported command is not
     *         supported.
     */
    public SerialMessage getSupportedMessage() {
        logger.debug("NODE {}: Creating new message for application command METER_SUPPORTED_GET",
                this.getNode().getNodeId());
        SerialMessage result = new SerialMessage(this.getNode().getNodeId(), SerialMessageClass.SendData,
                SerialMessageType.Request, SerialMessageClass.ApplicationCommandHandler, SerialMessagePriority.Config);
        byte[] newPayload = { (byte) this.getNode().getNodeId(), 2, (byte) getCommandClass().getKey(),
                (byte) METER_SUPPORTED_GET };
        result.setMessagePayload(newPayload);
        return result;
    }

    /**
     * Gets a SerialMessage with the METER_RESET command
     *
     * @return the serial message
     */
    public SerialMessage getResetMessage() {
        // ignore the reset if the version is less than one or meter is not
        // resetable
        if (this.getVersion() == 1 || !this.canReset) {
            return null;
        }

        logger.debug("NODE {}: Creating new message for application command METER_RESET", this.getNode().getNodeId());
        SerialMessage result = new SerialMessage(this.getNode().getNodeId(), SerialMessageClass.SendData,
                SerialMessageType.Request, SerialMessageClass.ApplicationCommandHandler, SerialMessagePriority.Set);
        byte[] newPayload = { (byte) this.getNode().getNodeId(), 2, (byte) getCommandClass().getKey(),
                (byte) METER_RESET };
        result.setMessagePayload(newPayload);
        return result;
    }

    /**
     * Initializes the meter command class. Requests the supported meter types.
     */
    @Override
    public Collection<SerialMessage> initialize(boolean refresh) {
        ArrayList<SerialMessage> result = new ArrayList<SerialMessage>();
        // If we're already initialized, then don't do it again unless we're refreshing
        if (isSupportRequestSupported == true && (refresh == true || initialiseDone == false)
                && this.getVersion() > 1) {
            result.add(this.getSupportedMessage());
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<SerialMessage> getDynamicValues(boolean refresh) {
        ArrayList<SerialMessage> result = new ArrayList<SerialMessage>();

        if (refresh == true || dynamicDone == false) {
            switch (this.getVersion()) {
                case 1:
                    result.add(this.getValueMessage());
                    break;
                case 2:
                case 3:
                    for (MeterScale scale : this.meterScales) {
                        result.add(this.getMessage(scale));
                    }
                    break;
            }
        }

        return result;
    }

    /**
     * Z-Wave MeterType enumeration. The meter type indicates the type of meter
     * that is reported.
     *
     * @author Ben Jones
     * @since 1.4.0
     */
    public enum MeterType {
        UNKNOWN(0, "Unknown"),
        ELECTRIC(1, "Electric"),
        GAS(2, "Gas"),
        WATER(3, "Water");

        /**
         * A mapping between the integer code and its corresponding Meter type
         * to facilitate lookup by code.
         */
        private static Map<Integer, MeterType> codeToMeterTypeMapping;

        private int key;
        private String label;

        private MeterType(int key, String label) {
            this.key = key;
            this.label = label;
        }

        private static void initMapping() {
            codeToMeterTypeMapping = new HashMap<Integer, MeterType>();
            for (MeterType s : values()) {
                codeToMeterTypeMapping.put(s.key, s);
            }
        }

        /**
         * Lookup function based on the meter type code. Returns null if the
         * code does not exist.
         *
         * @param i
         *            the code to lookup
         * @return enumeration value of the meter type.
         */
        public static MeterType getMeterType(int i) {
            if (codeToMeterTypeMapping == null) {
                initMapping();
            }

            return codeToMeterTypeMapping.get(i);
        }

        /**
         * @return the key
         */
        public int getKey() {
            return key;
        }

        /**
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * Z-Wave MeterScale enumeration. The meter scale indicates the meter scale
     * that is reported.
     *
     * @author Jan-Willem Spuij
     * @since 1.4.0
     */
    @XStreamAlias("meterScale")
    public enum MeterScale {
        E_KWh(0, MeterType.ELECTRIC, "kWh", "Energy"),
        E_KVAh(1, MeterType.ELECTRIC, "kVAh", "Energy"),
        E_W(2, MeterType.ELECTRIC, "W", "Power"),
        E_Pulses(3, MeterType.ELECTRIC, "Pulses", "Count"),
        E_V(4, MeterType.ELECTRIC, "V", "Voltage"),
        E_A(5, MeterType.ELECTRIC, "A", "Current"),
        E_Power_Factor(6, MeterType.ELECTRIC, "Power Factor", "Power Factor"),
        G_Cubic_Meters(0, MeterType.GAS, "Cubic Meters", "Volume"),
        G_Cubic_Feet(1, MeterType.GAS, "Cubic Feet", "Volume"),
        G_Pulses(3, MeterType.GAS, "Pulses", "Count"),
        W_Cubic_Meters(0, MeterType.WATER, "Cubic Meters", "Volume"),
        W_Cubic_Feet(1, MeterType.WATER, "Cubic Feet", "Volume"),
        W_Gallons(2, MeterType.WATER, "US gallons", "Volume"),
        W_Pulses(3, MeterType.WATER, "Pulses", "Count");

        private final int scale;
        private final MeterType meterType;
        private final String unit;
        private final String label;

        /**
         * A mapping between the integer code, Meter type and its corresponding Meter scale
         * to facilitate lookup by code.
         */
        private static Map<MeterType, Map<Integer, MeterScale>> codeToMeterScaleMapping;

        /**
         * A mapping between the name,and its corresponding Meter scale
         * to facilitate lookup by enumeration name.
         */
        private static Map<String, MeterScale> nameToMeterScaleMapping;

        /**
         * Constructor. Creates a new enumeration value.
         *
         * @param scale the scale number
         * @param meterType the meter type
         * @param unit the unit
         * @param label the label.
         */
        private MeterScale(int scale, MeterType meterType, String unit, String label) {
            this.scale = scale;
            this.meterType = meterType;
            this.unit = unit;
            this.label = label;
        }

        private static void initMapping() {
            codeToMeterScaleMapping = new HashMap<MeterType, Map<Integer, MeterScale>>();
            nameToMeterScaleMapping = new HashMap<String, MeterScale>();
            for (MeterScale s : values()) {
                if (!codeToMeterScaleMapping.containsKey(s.getMeterType())) {
                    codeToMeterScaleMapping.put(s.getMeterType(), new HashMap<Integer, MeterScale>());
                }
                codeToMeterScaleMapping.get(s.getMeterType()).put(s.getScale(), s);
                nameToMeterScaleMapping.put(s.name().toLowerCase(), s);
            }
        }

        /**
         * Lookup function based on the meter type and code. Returns null if the
         * code does not exist.
         *
         * @param meterType the meter type to use to lookup the scale
         * @param i the code to lookup
         * @return enumeration value of the meter scale.
         */
        public static MeterScale getMeterScale(MeterType meterType, int i) {
            if (codeToMeterScaleMapping == null) {
                initMapping();
            }
            if (!codeToMeterScaleMapping.containsKey(meterType)) {
                return null;
            }

            return codeToMeterScaleMapping.get(meterType).get(i);
        }

        /**
         * Lookup function based on the name. Returns null if the
         * name does not exist.
         *
         * @param name the name to lookup
         * @return enumeration value of the meter scale.
         */
        public static MeterScale getMeterScale(String name) {
            if (nameToMeterScaleMapping == null) {
                initMapping();
            }

            return nameToMeterScaleMapping.get(name.toLowerCase());
        }

        /**
         * Returns the scale code.
         *
         * @return the scale code.
         */
        protected int getScale() {
            return scale;
        }

        /**
         * Returns the meter type.
         *
         * @return the meterType
         */
        protected MeterType getMeterType() {
            return meterType;
        }

        /**
         * Returns the unit as string.
         *
         * @return the unit
         */
        protected String getUnit() {
            return unit;
        }

        /**
         * Returns the label (category).
         *
         * @return the label
         */
        protected String getLabel() {
            return label;
        }

    }

    /**
     * Z-Wave Meter value Event class. Indicates that a meter value changed.
     *
     * @author Jan-Willem Spuij
     * @since 1.4.0
     */
    public class ZWaveMeterValueEvent extends ZWaveCommandClassValueEvent {

        private MeterType meterType;
        private MeterScale meterScale;

        /**
         * Constructor. Creates a instance of the ZWaveMeterValueEvent class.
         *
         * @param nodeId
         *            the nodeId of the event
         * @param endpoint
         *            the endpoint of the event.
         * @param meterType
         *            the meter type that triggered the event;
         * @param meterType
         *            the meter scale for the event;
         * @param value
         *            the value for the event.
         */
        private ZWaveMeterValueEvent(int nodeId, int endpoint, MeterType meterType, MeterScale meterScale,
                Object value) {
            super(nodeId, endpoint, CommandClass.METER, value);
            this.meterType = meterType;
            this.meterScale = meterScale;
        }

        /**
         * Gets the meter type for this meter value event.
         *
         * @return the meter type for this meter value event.
         */
        public MeterType getMeterType() {
            return meterType;
        }

        /**
         * Gets the meter scale for this meter value event.
         *
         * @return the meter scale for this meter value event.
         */
        public MeterScale getMeterScale() {
            return meterScale;
        }

    }
}
