/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2018.
 */

package ch.sbb.matsim.mobsim.qsim;

import java.util.ArrayList;
import java.util.Collection;

import org.apache.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.mobsim.qsim.AbstractQSimPlugin;
import org.matsim.core.mobsim.qsim.ActivityEnginePlugin;
import org.matsim.core.mobsim.qsim.PopulationPlugin;
import org.matsim.core.mobsim.qsim.TeleportationPlugin;
import org.matsim.core.mobsim.qsim.changeeventsengine.NetworkChangeEventsPlugin;
import org.matsim.core.mobsim.qsim.messagequeueengine.MessageQueuePlugin;
import org.matsim.core.mobsim.qsim.pt.ComplexTransitStopHandlerFactory;
import org.matsim.core.mobsim.qsim.pt.TransitStopHandlerFactory;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEnginePlugin;

import com.google.inject.Provides;

import ch.sbb.matsim.mobsim.qsim.pt.SBBTransitEnginePlugin;
import ch.sbb.matsim.mobsim.qsim.pt.SBBTransitQSimEngine;

/**
 * @author mrieser / SBB
 */
public class SBBQSimModule extends com.google.inject.AbstractModule {

    private static final Logger log = Logger.getLogger(SBBQSimModule.class);

    @Override
    protected void configure() {
        // let's hope the normal QSimModule's configuration still holds.
    	
    	// This needs to be here, since QSimModule defines also a TransitStopHandlerFactory.
    	// If the QSimPlugin defines a new one, the old one cannot be overridden (there will be 
    	// an exception). Therefore, this needs to be bound here, because then we override the
    	// default one. /sh feb 18
    	bind(TransitStopHandlerFactory.class).to(ComplexTransitStopHandlerFactory.class).asEagerSingleton();
    }

    // @SuppressWarnings("static-method")
    @Provides
    Collection<AbstractQSimPlugin> provideQSimPlugins(Config config) {
        final Collection<AbstractQSimPlugin> plugins = new ArrayList<>();
        plugins.add(new MessageQueuePlugin(config));
        plugins.add(new ActivityEnginePlugin(config));
        plugins.add(new QNetsimEnginePlugin(config));
        if (config.network().isTimeVariantNetwork()) {
            plugins.add(new NetworkChangeEventsPlugin(config));
        }
        if (config.transit().isUseTransit()) {
            plugins.add(new SBBTransitEnginePlugin(config));
        }
        plugins.add(new TeleportationPlugin(config));
        plugins.add(new PopulationPlugin(config));
        return plugins;
    }

}
