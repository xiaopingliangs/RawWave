package com.rawwave.core;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
public class ActivityStoreTest {
    private Map<String,Object> eda(long stamp,long utc,long elapsed) {
        return Map.of("sdkTimestamp",stamp,"receivedUtcMs",utc,"receivedElapsedMs",elapsed);
    }
    @Test public void replayCode55RealEdaTailWithoutFlushCallback() {
        var gate=new EdaEndBoundary();
        gate.observe(List.of(eda(1793173128269L,1793173128434L,641246L)));
        gate.observe(List.of(eda(1793173129270L,1793173129396L,642208L)));
        gate.observe(List.of(eda(1793173130269L,1793173130342L,643154L)));
        gate.observe(List.of(eda(1793173131269L,1793173131311L,644123L)));
        gate.observe(List.of(eda(1793173132269L,1793173132746L,645559L)));
        gate.observe(List.of(eda(1793173133271L,1793173133711L,646524L)));
        gate.observe(List.of(eda(1793173134269L,1793173134672L,647484L)));
        gate.observe(List.of(eda(1793173135271L,1793173135642L,648454L)));
        gate.observe(List.of(eda(1793173136269L,1793173136589L,649401L)));
        gate.observe(List.of(eda(1793173137271L,1793173137557L,650369L)));
        gate.observe(List.of(eda(1793173138269L,1793173138515L,651328L)));
        gate.observe(List.of(eda(1793173139269L,1793173139468L,652280L)));
        gate.observe(List.of(eda(1793173140269L,1793173140433L,653245L)));
        gate.observe(List.of(eda(1793173141270L,1793173141388L,654200L)));
        gate.observe(List.of(eda(1793173142269L,1793173142350L,655162L)));
        gate.observe(List.of(eda(1793173143269L,1793173143315L,656127L)));
        gate.observe(List.of(eda(1793173144270L,1793173144752L,657564L)));
        gate.observe(List.of(eda(1793173145269L,1793173145718L,658530L)));
        gate.observe(List.of(eda(1793173146269L,1793173146670L,659482L)));
        gate.observe(List.of(eda(1793173147270L,1793173147621L,660434L)));
        gate.observe(List.of(eda(1793173148269L,1793173148595L,661407L)));
        gate.observe(List.of(eda(1793173149269L,1793173149547L,662359L)));
        gate.observe(List.of(eda(1793173150270L,1793173150516L,663328L)));
        gate.observe(List.of(eda(1793173151270L,1793173151480L,664292L)));
        gate.observe(List.of(eda(1793173152270L,1793173152445L,665257L)));
        gate.observe(List.of(eda(1793173153269L,1793173153393L,666205L)));
        gate.observe(List.of(eda(1793173154269L,1793173154356L,667168L)));
        gate.observe(List.of(eda(1793173155269L,1793173155305L,668117L)));
        gate.observe(List.of(eda(1793173156269L,1793173156760L,669572L)));
        gate.observe(List.of(eda(1793173157270L,1793173157718L,670530L)));
        gate.observe(List.of(eda(1793173158270L,1793173158672L,671484L)));
        gate.observe(List.of(eda(1793173159269L,1793173159630L,672443L)));
        gate.observe(List.of(eda(1793173160270L,1793173160593L,673405L)));
        gate.observe(List.of(eda(1793173161270L,1793173161548L,674361L)));
        gate.observe(List.of(eda(1793173162270L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173163269L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173164269L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173165270L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173166269L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173167269L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173168269L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173169270L,1793173170182L,682995L)));
        gate.observe(List.of(eda(1793173170270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173171270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173172270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173173270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173174270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173175270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173176270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173177270L,1793173178290L,691102L)));
        gate.observe(List.of(eda(1793173178270L,1793173183575L,696387L)));
        gate.observe(List.of(eda(1793173179270L,1793173183575L,696387L)));
        gate.observe(List.of(eda(1793173180270L,1793173183575L,696387L)));
        gate.observe(List.of(eda(1793173181270L,1793173183575L,696387L)));
        gate.observe(List.of(eda(1793173182270L,1793173183575L,696387L)));
        gate.observe(List.of(eda(1793173183270L,1793173183575L,696387L)));
        gate.observe(List.of(eda(1793173184269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173185269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173186269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173187269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173188269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173189269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173190269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173191269L,1793173191701L,704513L)));
        gate.observe(List.of(eda(1793173192270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173193270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173194270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173195270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173196270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173197270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173198270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173199270L,1793173199822L,712634L)));
        gate.observe(List.of(eda(1793173200269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173201269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173202269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173203269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173204269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173205269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173206269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173207269L,1793173207909L,720721L)));
        gate.observe(List.of(eda(1793173208269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173209269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173210269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173211269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173212269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173213269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173214269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173215269L,1793173215996L,728808L)));
        gate.observe(List.of(eda(1793173216269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173217269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173218269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173219269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173220269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173221269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173222269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173223269L,1793173224105L,736917L)));
        gate.observe(List.of(eda(1793173224270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173225270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173226270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173227270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173228270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173229270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173230270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173231270L,1793173232180L,744993L)));
        gate.observe(List.of(eda(1793173232270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173233270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173234270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173235270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173236270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173237270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173238270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173239270L,1793173240285L,753097L)));
        gate.observe(List.of(eda(1793173240270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173241270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173242270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173243270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173244270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173245270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173246270L,1793173247203L,760016L)));
        gate.observe(List.of(eda(1793173247269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173248269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173249269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173250269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173251269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173252269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173253269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173254269L,1793173255307L,768119L)));
        gate.observe(List.of(eda(1793173255270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173256270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173257270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173258270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173259270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173260270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173261270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173262270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173263270L,1793173263394L,776206L)));
        gate.observe(List.of(eda(1793173264270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173265270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173266270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173267270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173268270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173269270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173270270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173271270L,1793173271480L,784292L)));
        gate.observe(List.of(eda(1793173272270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173273270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173274270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173275270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173276270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173277270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173278270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173279270L,1793173279568L,792380L)));
        gate.observe(List.of(eda(1793173280269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173281269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173282269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173283269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173284269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173285269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173286269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173287269L,1793173287662L,800474L)));
        gate.observe(List.of(eda(1793173288269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173289269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173290269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173291269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173292269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173293269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173294269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173295269L,1793173295748L,808560L)));
        gate.observe(List.of(eda(1793173296270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173297270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173298270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173299270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173300270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173301270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173302270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173303270L,1793173303849L,816662L)));
        gate.observe(List.of(eda(1793173304269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173305269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173306269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173307269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173308269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173309269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173310269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173311269L,1793173311925L,824737L)));
        gate.observe(List.of(eda(1793173312270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173313270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173314270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173315270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173316270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173317270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173318270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173319270L,1793173320014L,832826L)));
        gate.observe(List.of(eda(1793173320270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173321270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173322270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173323270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173324270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173325270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173326270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173327270L,1793173328110L,840923L)));
        gate.observe(List.of(eda(1793173328269L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173329269L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173330269L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173331269L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173332269L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173333270L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173334270L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173335269L,1793173336230L,849042L)));
        gate.observe(List.of(eda(1793173336270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173337270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173338270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173339270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173340270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173341270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173342270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173343270L,1793173343637L,856450L)));
        gate.observe(List.of(eda(1793173344270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173345270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173346270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173347270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173348270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173349270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173350270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173351270L,1793173351732L,864544L)));
        gate.observe(List.of(eda(1793173352269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173353269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173354269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173355269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173356269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173357269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173358269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173359269L,1793173359822L,872634L)));
        gate.observe(List.of(eda(1793173360270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173361270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173362270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173363270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173364270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173365270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173366270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173367270L,1793173367941L,880753L)));
        gate.observe(List.of(eda(1793173368269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173369269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173370269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173371269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173372269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173373269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173374269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173375269L,1793173376036L,888848L)));
        gate.observe(List.of(eda(1793173376270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173377270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173378270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173379270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173380270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173381270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173382270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173383270L,1793173384166L,896978L)));
        gate.observe(List.of(eda(1793173384270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173385270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173386271L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173387270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173388270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173389270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173390270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173391270L,1793173392261L,905073L)));
        gate.observe(List.of(eda(1793173392270L,1793173392272L,905084L)));
        gate.observe(List.of(eda(1793173393270L,1793173393623L,906435L)));
        gate.observe(List.of(eda(1793173394269L,1793173394582L,907394L)));
        gate.observe(List.of(eda(1793173395270L,1793173395543L,908355L)));
        gate.arm(1793173396280L,909092L);
        gate.observe(List.of(eda(1793173396269L,1793173396312L,909124L)));
        gate.observe(List.of(eda(1793173397269L,1793173397477L,910289L)));
        gate.observe(List.of(eda(1793173398270L,1793173398424L,911236L)));
        gate.observe(List.of(eda(1793173399269L,1793173399376L,912188L)));
        gate.observe(List.of(eda(1793173400270L,1793173400356L,913168L)));
        assertTrue(gate.observe(List.of()));
    }
    @Test public void edaMissingFlushCanFinishOnlyAfterAdjacentBoundarySample() {
        var gate=new EdaEndBoundary();
        assertFalse(gate.observe(List.of(eda(9500,9600,600))));gate.arm(10000,1000);
        assertFalse(gate.observe(List.of()));
        assertTrue(gate.observe(List.of(eda(10500,10600,1600))));
        assertTrue(gate.evidence().contains("SDK_FLUSH_NOT_ACKNOWLEDGED"));
        var drain=new CaptureDrain(List.of("EDA","PPG"));
        drain.complete("EDA",SensorPort.DrainStatus.END_TIME_COVERED,gate.evidence());
        assertFalse(drain.claimReady());drain.complete("PPG",SensorPort.DrainStatus.FLUSHED,"ack");
        assertTrue(drain.succeeded());
    }
    @Test public void edaBoundaryRejectsGapsClockJumpsUnorderedAndMissingData() {
        for(int mode=0;mode<5;mode++) {
            var gate=new EdaEndBoundary();gate.observe(List.of(eda(9500,9600,600)));gate.arm(10000,1000);
            var row=switch(mode) {
                case 0 -> eda(11500,11600,2600);
                case 1 -> eda(10500,11600,1600);
                case 2 -> eda(9400,10600,1600);
                case 3 -> eda(11500,10600,1600);
                default -> Map.<String,Object>of("STATUS",0);
            };
            assertFalse(gate.observe(List.of(row)));
        }
        var drain=new CaptureDrain(List.of("HEART_RATE"));
        drain.complete("HEART_RATE",SensorPort.DrainStatus.END_TIME_COVERED,"wrong source");
        assertFalse(drain.succeeded());
    }
    @Test public void edaNoSamplesOrOnlyOldSamplesNeverComplete() {
        var gate=new EdaEndBoundary();gate.arm(10000,1000);
        assertFalse(gate.observe(List.of()));
        assertFalse(gate.observe(List.of(eda(8500,10100,1100),eda(9500,10100,1100))));
    }
    @Test public void editSelectedTemplatePreservesOtherTemplateAndHistoricalPlan() throws Exception {
        var folder=Files.createTempDirectory("template-edit-test").toFile();
        try {
            var store=new ActivityStore(folder,dir->{});
            var first=store.createTemplate("first",true);
            var second=store.createTemplate("second",false);
            var session=store.start(first,1000,1);
            byte[] plan=Files.readAllBytes(new File(session.folder,"plan.json").toPath());
            store.updateTemplate(first.id(),"changed",false,SensorCatalog.availableCoreSources(),true);
            var reopened=new ActivityStore(folder,dir->{});
            var edited=reopened.templates().stream().filter(t->t.id().equals(first.id())).findFirst().orElseThrow();
            assertEquals("changed",edited.name());assertFalse(edited.ecg());assertTrue(edited.spo2());
            assertEquals(SensorCatalog.availableCoreSources(),edited.coreSources());
            assertTrue(reopened.templates().contains(second));
            assertThrows(IOException.class,()->store.updateTemplate(first.id(),"",true,List.of("GYROSCOPE"),false));
            assertTrue(reopened.templates().contains(edited));
            store.deleteTemplate(first.id());assertEquals(List.of(second),reopened.templates());
            assertThrows(IOException.class,()->store.updateTemplate(first.id(),"missing",true,List.of("GYROSCOPE"),false));
            assertArrayEquals(plan,Files.readAllBytes(new File(session.folder,"plan.json").toPath()));
        } finally {
            try(var paths=Files.walk(folder.toPath())) {for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    @Test public void templateDeletePreservesRecordedPlanAndAllSensorsRemainSelectable() throws Exception {
        var folder=Files.createTempDirectory("template-delete-test").toFile();
        try {
            var store=new ActivityStore(folder,dir->{});
            var template=store.createTemplate("all sources",true,SensorCatalog.availableCoreSources(),true);
            var session=store.start(template,1000,1);
            session.append("PRE-100","SPO2",List.of(Map.of("STATUS",2,"SPO2",98)));
            session.append("CORE-1","GYROSCOPE",List.of(Map.of("sensorTimestampNs",1234567L,"GYROSCOPE_X",0.1)));
            session.seal("DONE",2000);
            byte[] plan=Files.readAllBytes(new File(session.folder,"plan.json").toPath());
            var loaded=store.templates().get(0);
            assertTrue(loaded.spo2());assertTrue(loaded.ecg());assertEquals(SensorCatalog.availableCoreSources(),loaded.coreSources());
            assertThrows(IOException.class,()->store.deleteTemplate("../sessions"));
            store.deleteTemplate(template.id());assertTrue(store.templates().isEmpty());
            assertArrayEquals(plan,Files.readAllBytes(new File(session.folder,"plan.json").toPath()));
            var recreated=store.createTemplate("new",false,List.of("GYROSCOPE"),true);
            assertEquals(List.of("GYROSCOPE"),recreated.coreSources());assertTrue(recreated.spo2());assertFalse(recreated.ecg());
        } finally {
            try(var paths=Files.walk(folder.toPath())) {for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    @Test public void drainPersistenceFailureNeverReleasesNormalNextPhase() {
        var drain=new CaptureDrain(List.of("ECG"));
        drain.complete("ECG",SensorPort.DrainStatus.DELIVERED_ONLY,"measurement checked separately");
        var advanced=new AtomicBoolean();var error=new AtomicReference<String>();
        assertTrue(drain.persist(()->{throw new IOException("injected fsync failure");},failure->{
            if(failure==null)advanced.set(true);else error.set(failure);
        }));
        assertFalse(advanced.get());assertTrue(error.get().contains("DRAIN_SAVE_FAILED"));
        assertFalse(drain.persist(()->fail("must not retry silently"),failure->fail("duplicate")));
    }
    @Test public void drainSdkFailureStillSavesRowsButDoesNotAdvanceNormally() {
        var drain=new CaptureDrain(List.of("EDA"));var saved=new AtomicBoolean();
        drain.complete("EDA",SensorPort.DrainStatus.FAILED,"flush rejected");
        drain.persist(()->saved.set(true),failure->{assertTrue(saved.get());assertTrue(failure.contains("flush rejected"));});
    }
    @Test public void drainDoesNotPersistBeforeLastSourceAndPropagatesNextPhaseFailure() {
        var drain=new CaptureDrain(List.of("PPG"));
        assertFalse(drain.persist(()->fail("too early"),failure->fail("too early")));
        drain.complete("PPG",SensorPort.DrainStatus.FLUSHED,"ack");
        assertThrows(IllegalStateException.class,()->drain.persist(()->{},failure->{
            assertNull(failure);throw new IllegalStateException("next phase failure");
        }));
    }
    @Test public void acceptedCallbackCopyAndPostCannotBeOvertakenByShutdown() throws Exception {
        var fence=new SensorCallbackFence();var queue=new ConcurrentLinkedQueue<Runnable>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopEntered=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);var order=new ArrayList<String>();
        try {
            var copying=pool.submit(()->fence.accept(()->{
                entered.countDown();
                try { if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("test timeout"); }
                catch(InterruptedException e){throw new AssertionError(e);}
                queue.add(()->order.add("raw rows"));
            }));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var stopping=pool.submit(()->{
                stopEntered.countDown();fence.close();queue.add(()->order.add("drain completion"));
            });
            assertTrue(stopEntered.await(3,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->stopping.get(50,TimeUnit.MILLISECONDS));
            release.countDown();copying.get(3,TimeUnit.SECONDS);stopping.get(3,TimeUnit.SECONDS);
            fence.accept(()->fail("post-stop callback cannot revive this source"));
            queue.forEach(Runnable::run);
            assertEquals(List.of("raw rows","drain completion"),order);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void acceptedEcgRowsReachRealDiskBeforePostPhaseCanStart() throws Exception {
        var session=store().start(store().createTemplate("短测保存交接",true),1,2);
        var fence=new SensorCallbackFence();var queue=new ArrayDeque<Runnable>();
        var drain=new CaptureDrain(List.of("ECG"));var buffers=new ArrayList<Map<String,Object>>();
        fence.accept(()->queue.add(()->buffers.add(Map.of("sdkTimestamp",123,"ECG_MV",0.25))));
        fence.close();
        queue.add(()->{
            drain.complete("ECG",SensorPort.DrainStatus.DELIVERED_ONLY,"no SDK flush");
            drain.persist(()->{session.append("PRE-1","ECG",buffers);buffers.clear();},failure->{
                assertNull(failure);assertEquals(Long.valueOf(1),session.counts().get("PRE-1/ECG"));
                assertTrue(new File(session.folder,"00000001-PRE-1-ECG.json").isFile());
            });
        });
        while(!queue.isEmpty())queue.remove().run();
        assertTrue(buffers.isEmpty());
    }
    @Test public void captureOldFailureCannotEndRestartedSensorEvenInSamePhase() {
        var runs=new CaptureRuns();var old=runs.start("CORE-1","PPG");
        runs.endSensor("PPG");var current=runs.start("CORE-1","PPG");
        assertFalse(runs.end(old));assertTrue(runs.current(current));
        assertEquals("CORE-1",old.phase);assertEquals("PPG",old.sensor);
    }
    @Test public void captureStopInvalidatesBeforeSdkCallbackAndDoesNotStopOtherSource() {
        var runs=new CaptureRuns();var ecg=runs.start("PRE-1","ECG");var hr=runs.start("CORE-1","HEART_RATE");
        runs.endSensor("ECG");assertFalse(runs.current(ecg));assertFalse(runs.end(ecg));assertTrue(runs.current(hr));
        assertEquals("PRE-1",ecg.phase);
    }
    @Test public void captureDuplicateStartAndDuplicateFailureCannotReplaceOwner() {
        var runs=new CaptureRuns();var first=runs.start("PRE-1","ECG");
        assertThrows(IllegalStateException.class,()->runs.start("POST-1","ECG"));
        assertTrue(runs.current(first));assertTrue(runs.end(first));assertFalse(runs.end(first));
    }
    @Test public void captureIdleExpiryCannotAddAnotherReviewWindow() {
        for(String phase:List.of("PRE-1","PRE-2","POST-1","REVIEW_PRE","REVIEW_POST"))
            assertEquals(CapturePhasePolicy.Next.IDLE_STOP,CapturePhasePolicy.expired(phase,false));
    }
    @Test public void captureMeasuringExpiryEndsAttemptAndConnectExpiryIsSeparate() {
        assertEquals(CapturePhasePolicy.Next.END_ECG,CapturePhasePolicy.expired("PRE-1",true));
        assertEquals(CapturePhasePolicy.Next.END_ECG,CapturePhasePolicy.expired("POST-2",true));
        assertEquals(CapturePhasePolicy.Next.CONNECT_TIMEOUT,CapturePhasePolicy.expired("CONNECTING",false));
        assertThrows(IllegalArgumentException.class,()->CapturePhasePolicy.expired("CORE-1",false));
    }
    @Test public void captureEcgSuccessAdvancesButFailuresRequireReview() {
        assertEquals(CapturePhasePolicy.Next.CORE,CapturePhasePolicy.ecgEnded(true,false,true));
        assertEquals(CapturePhasePolicy.Next.FINISH,CapturePhasePolicy.ecgEnded(false,false,true));
        for(boolean complete:List.of(false,true)) {
            assertEquals(CapturePhasePolicy.Next.REVIEW_PRE,CapturePhasePolicy.ecgEnded(true,true,complete));
            assertEquals(CapturePhasePolicy.Next.REVIEW_POST,CapturePhasePolicy.ecgEnded(false,true,complete));
        }
        assertEquals(CapturePhasePolicy.Next.REVIEW_POST,CapturePhasePolicy.ecgEnded(false,false,false));
    }
    @Test public void captureCoreEndUsesOriginalTemplateChoice() {
        assertEquals(CapturePhasePolicy.Next.POST_ECG,CapturePhasePolicy.coreEnded(true));
        assertEquals(CapturePhasePolicy.Next.FINISH,CapturePhasePolicy.coreEnded(false));
    }
    @Test public void diskHandshakeBothKindsPreservesExactBytes() throws Exception { diskHandshake("NONE"); }
    @Test public void diskHandshakeReceiveDurabilityFailureNeverAcknowledgesSender() throws Exception { diskHandshake("DURABILITY"); }
    @Test public void diskHandshakeLocalReceiptFailureKeepsPhoneCopyAndSource() throws Exception { diskHandshake("LOCAL_ACK"); }
    @Test public void diskHandshakeLostConfirmKeepsBothDurableCopies() throws Exception { diskHandshake("CONFIRM"); }
    @Test public void diskHandshakeLostFinishedDoesNotEraseSavedAcknowledgement() throws Exception { diskHandshake("FINISHED"); }
    @Test public void diskHandshakeCorruptionCannotProduceSuccessfulReceipt() throws Exception { diskHandshake("CORRUPT"); }

    /** Real disk adapters + admission + final exchange; signal queues replace only delivery.
     * Not an Android coordinator, JSON envelope, radio, or directory-fsync platform test. */
    private void diskHandshake(String fault) throws Exception {
        for(var kind:ReceiveStore.Kind.values()) {
            File watch=temp.newFolder(),phone=temp.newFolder();
            if(kind==ReceiveStore.Kind.RAW_ACTIVITY) {
                var activities=new ActivityStore(new File(watch,"activities"),d->{});
                var session=activities.start(activities.createTemplate("联动",false),1000,1);
                session.append("CORE-1","HEART_RATE",List.of(Map.of("HEART_RATE",73,"HEART_RATE_STATUS",-1,"sdkTimestamp",123)));
                session.seal("DONE",2000);
            }
            SendStore sender=new FileSendStore(watch,kind,d->{if(fault.equals("LOCAL_ACK"))throw new IOException("ACK_SYNC_FAILED");});
            // Preparation is not the injected ACK failure boundary.
            SendStore preparer=new FileSendStore(watch,kind,d->{});
            var payload=preparer.prepare(id->Map.of("diagnostic.txt","original evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            byte[] original=Files.readAllBytes(payload.file().toPath());
            byte[] wire;
            try(var input=sender.open(payload)){wire=input.readAllBytes();}
            assertArrayEquals(original,wire);
            if(fault.equals("CORRUPT"))wire[wire.length/2]^=1;
            var sendAttempt=new TransferAttempt(true,false,100);
            var receiveAttempt=new TransferAttempt(false,false,100);
            var toWatch=new TransferAttempt.Identity("phone","window",payload.id(),payload.sha256(),payload.bytes());
            var toPhone=new TransferAttempt.Identity("watch","window",payload.id(),payload.sha256(),payload.bytes());
            sendAttempt.bindSender(toWatch,1);
            assertEquals(TransferAttempt.Action.ACCEPT_HELLO,receiveAttempt.accept("HELLO",toPhone,1));
            assertEquals(TransferAttempt.Action.READY,sendAttempt.accept("READY",toWatch,1));
            assertTrue(receiveAttempt.acceptChannel("watch","/file/window/"+payload.id(),"/file/",1));
            var outbound=new java.util.concurrent.LinkedBlockingQueue<String>();
            var inbound=new java.util.concurrent.LinkedBlockingQueue<String>();
            ReceiveStore receiver=new FileReceiveStore(phone,d->{if(fault.equals("DURABILITY"))throw new IOException("PHONE_SYNC_FAILED");});
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var remote=pool.submit(()->{
                    try {
                        return CommitExchange.receiver(()->{
                            var receipt=receiver.commit(new ReceiveStore.Request(kind,payload.id(),payload.sha256(),payload.bytes()),new ByteArrayInputStream(wire),()->{});
                            assertTrue(receiveAttempt.receiptSaved(1));return receipt;
                        },()->outbound.add("COMMITTED"),()->{
                            String message=inbound.poll(3,java.util.concurrent.TimeUnit.SECONDS);
                            if(!"CONFIRM".equals(message))throw new IOException("CONFIRM_NOT_DELIVERED");
                            assertEquals(TransferAttempt.Action.CONFIRM,receiveAttempt.accept(message,toPhone,1));
                        },()->outbound.add(fault.equals("FINISHED")?"LOST":"FINISHED"));
                    } catch(Exception error){outbound.add("FAILED");throw error;}
                });
                Exception localFailure=null;
                try {
                    CommitExchange.sender(()->{
                        String message=outbound.poll(3,java.util.concurrent.TimeUnit.SECONDS);
                        if(!"COMMITTED".equals(message))throw new IOException("NO_COMMIT");
                        assertEquals(TransferAttempt.Action.COMMITTED,sendAttempt.accept(message,toWatch,1));
                    },()->sender.acknowledge(payload,new SendStore.Confirmation(payload.id(),payload.sha256(),payload.bytes(),"phone",2000)),
                    ()->inbound.add(fault.equals("CONFIRM")?"LOST":"CONFIRM"),()->{
                        String message=outbound.poll(3,java.util.concurrent.TimeUnit.SECONDS);
                        if(!"FINISHED".equals(message))throw new IOException("NO_FINISHED");
                        assertEquals(TransferAttempt.Action.FINISHED,sendAttempt.accept(message,toWatch,1));
                    });
                }catch(Exception error){localFailure=error;inbound.offer("FAILED");}
                boolean remoteSucceeded;
                try { remote.get(4,java.util.concurrent.TimeUnit.SECONDS);remoteSucceeded=true; }
                catch(java.util.concurrent.ExecutionException error){assertTrue(error.getCause() instanceof IOException);remoteSucceeded=false;}
                assertEquals(fault,fault.equals("NONE"),localFailure==null);
                assertEquals(fault,fault.equals("NONE")||fault.equals("FINISHED"),remoteSucceeded);
                assertArrayEquals(original,Files.readAllBytes(payload.file().toPath()));
                if(!fault.equals("CORRUPT")) {
                    File saved=new File(phone,(kind==ReceiveStore.Kind.RAW_ACTIVITY?"activity-inbox/":"received/")+payload.id()+".zip");
                    assertArrayEquals(original,Files.readAllBytes(saved.toPath()));
                }
                if(fault.equals("NONE")||fault.equals("CONFIRM")||fault.equals("FINISHED")) {
                    assertTrue(new File(payload.file().getParentFile(),payload.id()+".ack.json").isFile());
                    if(kind==ReceiveStore.Kind.RAW_ACTIVITY)assertNull(preparer.prepare(null));
                }
                if(fault.equals("DURABILITY")||fault.equals("CORRUPT")) {
                    assertFalse(new File(payload.file().getParentFile(),payload.id()+".ack.json").exists());
                    assertEquals(payload.sha256(),preparer.prepare(null).sha256());
                }
            } finally {sendAttempt.close();receiveAttempt.close();pool.shutdownNow();}
        }
    }
    @Test public void sensorSnapshotPreservesNullInvalidAndFutureFields() {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("sdkTimestamp",123L); row.put("ECG_MV",-827.4946f);
        row.put("LEAD_OFF",5); row.put("missing",null); row.put("futureField",List.of(1,2));
        var copy=SensorRows.snapshot(List.of(row));
        assertEquals(row,copy.get(0));
        assertTrue(copy.get(0).get("ECG_MV") instanceof Float);
        assertTrue(copy.get(0).containsKey("missing"));
    }
    @Test public void sensorSnapshotOwnsRowsAndNestedLists() {
        var ibi=new ArrayList<Object>(Arrays.asList(800,null,810));
        Map<String,Object> row=new LinkedHashMap<>(); row.put("IBI_LIST",ibi);
        var source=new ArrayList<Map<String,Object>>(); source.add(row);
        var copy=SensorRows.snapshot(source);
        ibi.clear(); row.clear(); source.clear();
        assertEquals(Arrays.asList(800,null,810),copy.get(0).get("IBI_LIST"));
        assertThrows(UnsupportedOperationException.class,()->copy.clear());
        assertThrows(UnsupportedOperationException.class,()->copy.get(0).clear());
        assertThrows(UnsupportedOperationException.class,()->((List<?>)copy.get(0).get("IBI_LIST")).clear());
    }
    @Test public void sensorSnapshotRejectsOpaqueSdkObjectsInsteadOfInventingValues() {
        assertThrows(IllegalArgumentException.class,()->SensorRows.snapshot(List.of(Map.of("sdkObject",new Object()))));
        assertTrue(SensorRows.snapshot(List.of()).isEmpty());
    }
    private static class ReplacementSensor implements SensorPort {
        final Map<String,Rows> outputs=new HashMap<>();
        final Map<String,Failure> failures=new HashMap<>();
        int version=CONTRACT_VERSION; boolean connected,closed;
        public int contractVersion(){return version;}
        public String implementationVersion(){return "test-replacement/1";}
        public void connect(Runnable ready,Failure disconnected){connected=true;ready.run();}
        public void start(String sensor,Rows rows,Failure failed){outputs.put(sensor,rows);failures.put(sensor,failed);}
        public void stop(String sensor){outputs.remove(sensor);failures.remove(sensor);}
        public void drain(String sensor,Drain done){stop(sensor);done.accept(DrainStatus.FLUSHED,"fake");}
        public void close(){closed=true;outputs.clear();failures.clear();}
        void emit(String sensor,List<Map<String,Object>> rows){outputs.get(sensor).accept(SensorRows.snapshot(rows));}
    }
    @Test public void drainWaitsForEverySourceAndIgnoresDuplicateAndForeignCompletion() {
        var d=new CaptureDrain(List.of("HEART_RATE","PPG","EDA"));
        assertTrue(d.complete("PPG",SensorPort.DrainStatus.FLUSHED,"ack"));
        assertFalse(d.claimReady());
        assertFalse(d.complete("PPG",SensorPort.DrainStatus.FAILED,"late duplicate"));
        assertFalse(d.complete("ECG",SensorPort.DrainStatus.FLUSHED,"foreign"));
        d.complete("EDA",SensorPort.DrainStatus.FLUSHED,"zero samples still acknowledged");
        assertFalse(d.claimReady());
        d.complete("HEART_RATE",SensorPort.DrainStatus.FLUSHED,"ack");
        assertTrue(d.claimReady());assertTrue(d.succeeded());assertFalse(d.claimReady());
    }
    @Test public void drainTimeoutCannotBeRevivedByLateSuccess() {
        var d=new CaptureDrain(List.of("PPG","EDA"));
        d.complete("PPG",SensorPort.DrainStatus.FLUSHED,"ack");d.timeout();
        assertTrue(d.claimReady());assertFalse(d.succeeded());
        assertEquals(Set.of("EDA"),d.failures().keySet());
        assertFalse(d.complete("EDA",SensorPort.DrainStatus.FLUSHED,"late"));
        assertFalse(d.succeeded());
    }
    @Test public void continuousSourceCannotReplaceFlushWithDeliveredOnly() {
        var d=new CaptureDrain(List.of("PPG","ECG"));
        d.complete("PPG",SensorPort.DrainStatus.DELIVERED_ONLY,"no flush");
        d.complete("ECG",SensorPort.DrainStatus.DELIVERED_ONLY,"no SDK support");
        assertTrue(d.claimReady());assertFalse(d.succeeded());
        assertEquals(Set.of("PPG"),d.failures().keySet());
    }
    @Test public void failedDrainRetainsOtherSourceCompletionButCannotAdvanceNormally() {
        var d=new CaptureDrain(List.of("PPG","EDA"));
        d.complete("PPG",SensorPort.DrainStatus.FAILED,"flush rejected");
        assertFalse(d.claimReady());
        d.complete("EDA",SensorPort.DrainStatus.FLUSHED,"ack");
        assertTrue(d.claimReady());assertFalse(d.succeeded());
        assertEquals("flush rejected",d.failures().get("PPG"));
    }
    @Test public void emptyDrainIsReadyOnceWithoutInventingSamples() {
        var d=new CaptureDrain(List.of());assertTrue(d.claimReady());assertTrue(d.succeeded());
        assertFalse(d.claimReady());
    }
    @Test public void staggeredSourceTailIsPersistedBeforeSessionSeal() throws Exception {
        var s=store().start(store().createTemplate("错峰尾部",false,List.of("PPG","HEART_RATE")),1,2);
        var d=new CaptureDrain(List.of("PPG","HEART_RATE"));
        s.append("CORE-1","HEART_RATE",List.of(Map.of("sdkTimestamp",100,"HEART_RATE",72)));
        d.complete("HEART_RATE",SensorPort.DrainStatus.FLUSHED,"ack");
        assertFalse(d.claimReady());
        s.append("CORE-1","PPG",List.of(Map.of("sdkTimestamp",105,"PPG_GREEN",17)));
        d.complete("PPG",SensorPort.DrainStatus.FLUSHED,"ack");
        assertTrue(d.claimReady());assertTrue(d.succeeded());s.seal("DRAINED",3);
        assertEquals(Long.valueOf(1),s.counts().get("CORE-1/PPG"));
        assertEquals(Long.valueOf(1),s.counts().get("CORE-1/HEART_RATE"));
    }
    @Test public void incompatibleSensorRejectedBeforeHardwareConnection() {
        var fake=new ReplacementSensor();fake.version=1;
        assertThrows(IllegalArgumentException.class,()->SensorPort.requireCompatible(fake));
        assertFalse(fake.connected);
    }
    @Test public void replacementSensorWritesThroughExistingStoreWithoutVendorTypes() throws Exception {
        var fake=new ReplacementSensor();SensorPort port=SensorPort.requireCompatible(fake);
        var s=store().start(store().createTemplate("替换演练",false,List.of("HEART_RATE","PPG")),1,2);
        port.connect(()->{},reason->fail(reason));
        for(String sensor:List.of("HEART_RATE","PPG")) port.start(sensor,rows->{
            try{s.append("CORE-1",sensor,rows);}catch(IOException e){throw new UncheckedIOException(e);}
        },reason->{});
        fake.failures.get("PPG").accept("UNSUPPORTED_PPG");port.stop("PPG");
        fake.emit("HEART_RATE",List.of(Map.of("HEART_RATE",73,"HEART_RATE_STATUS",1,"sdkTimestamp",123L)));
        port.close();s.seal("PARTIAL_PPG_UNSUPPORTED",3);
        assertEquals(Long.valueOf(1),s.counts().get("CORE-1/HEART_RATE"));
        assertFalse(s.counts().containsKey("CORE-1/PPG"));
        assertTrue(Files.readString(new File(s.folder,"00000001-CORE-1-HEART_RATE.json").toPath()).contains("\"HEART_RATE\":73"));
        assertTrue(fake.closed);
    }
    @Test public void snapshotKeepsBatchTimestampAndIndexInsteadOfInventingPerSampleTime() {
        var rows=List.<Map<String,Object>>of(Map.of("sdkTimestamp",100L,"captureCallbackIndex",0,"ECG_MV",1f),
                Map.of("sdkTimestamp",100L,"captureCallbackIndex",1,"ECG_MV",2f));
        assertEquals(rows,SensorRows.snapshot(rows));
    }
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ActivityStore store() throws Exception {return new ActivityStore(temp.getRoot(),d->{});}
    @Test public void templateSurvivesRestart() throws Exception {var t=store().createTemplate("步行",true); assertEquals(t,store().templates().get(0));}
    @Test public void selectedSensorsSurviveRestart() throws Exception {
        var t=store().createTemplate("跑步",true,List.of("EDA","PPG","ACCELEROMETER"));
        assertEquals(List.of("ACCELEROMETER","PPG","EDA"),t.coreSources());
        assertEquals(t,store().templates().get(0));
    }
    @Test public void legacyTemplateMigratesToHeartRateAndAcceleration() throws Exception {
        File folder=new File(temp.getRoot(),"templates");assertTrue(folder.mkdirs());
        Files.writeString(new File(folder,"old.json").toPath(),"{\"id\":\"old\",\"name\":\"旧活动\",\"ecg\":false}");
        assertEquals(List.of("HEART_RATE","ACCELEROMETER"),store().templates().get(0).coreSources());
    }
    @Test public void rejectsTemplateWithNoCoreSensor() throws Exception {
        assertThrows(IOException.class,()->store().createTemplate("空",true,List.of()));
    }
    @Test public void rejectsEmptyName() throws Exception {assertThrows(IOException.class,()->store().createTemplate(" ",true));}
    @Test public void rawInvalidDataNotFilteredOrInvented() throws Exception {
        var s=store().start(store().createTemplate("测试",true),1,2);
        s.append("PRE-1","ECG",List.of(Map.of("LEAD_OFF",5,"ECG_MV",-0.25,"sdkTimestamp",123)));
        String raw=Files.readString(new File(s.folder,"00000001-PRE-1-ECG.json").toPath());
        assertTrue(raw.contains("-0.25"));assertTrue(raw.contains("\"LEAD_OFF\":5")); assertEquals(Long.valueOf(1),s.counts().get("PRE-1/ECG"));
    }
    @Test public void planSeparatesSdkRawValuesFromComputedQuality() throws Exception {
        var s=store().start(store().createTemplate("测试",true),1,2);
        String plan=Files.readString(new File(s.folder,"plan.json").toPath());
        assertTrue(plan.contains("without filtering, interpolation, invented samples or unit conversion"));
        assertTrue(plan.contains("not raw PPG")); assertTrue(plan.contains("quality metadata is separate"));
    }
    @Test public void planAndStorageIncludeOnlySelectedNewStreams() throws Exception {
        var s=store().start(store().createTemplate("扩展",false,List.of("PPG","SKIN_TEMPERATURE","EDA")),1,2);
        s.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",1,"PPG_IR",2,"PPG_RED",3)));
        s.append("CORE-1","SKIN_TEMPERATURE",List.of(Map.of("OBJECT_TEMPERATURE",32.5)));
        s.append("CORE-1","EDA",List.of(Map.of("SKIN_CONDUCTANCE",1.2)));
        String plan=Files.readString(new File(s.folder,"plan.json").toPath());
        assertTrue(plan.contains("raw green, infrared and red PPG"));
        assertTrue(plan.contains("not body temperature")); assertTrue(plan.contains("raw skin conductance"));
        assertFalse(plan.contains("HEART_RATE\":\"Samsung SDK"));
    }
    @Test public void unsealedDataSurvivesNewStore() throws Exception {var s=store().start(store().createTemplate("运动",false),1,2);s.append("CORE-1","HEART_RATE",List.of(Map.of("HEART_RATE",70)));assertTrue(store().history().get(0).contains("尾部未知"));assertEquals(2,s.folder.listFiles().length);}
    @Test public void sealVerifiesAndRejectsLaterWrites() throws Exception {var s=store().start(store().createTemplate("运动",false),1,2);s.append("CORE-1","EVENT",List.of(Map.of("reason","SENSOR_FAILED")));s.seal("PARTIAL",3);assertTrue(new File(s.folder,"seal.json").exists());assertThrows(IOException.class,()->s.append("CORE-1","EVENT",List.of(Map.of("x",1))));}
    @Test public void tamperedBlockCannotSeal() throws Exception {var s=store().start(store().createTemplate("运动",false),1,2);s.append("CORE-1","HEART_RATE",List.of(Map.of("value",70)));Files.writeString(new File(s.folder,"00000001-CORE-1-HEART_RATE.json").toPath(),"bad");assertThrows(IOException.class,()->s.seal("DONE",3));assertFalse(new File(s.folder,"seal.json").exists());}
    @Test public void traversalRejected() throws Exception {var s=store().start(store().createTemplate("运动",false),1,2);assertThrows(IOException.class,()->s.append("../../x","ECG",List.of(Map.of("x",1))));}
    @Test public void repeatedMeasurementsHaveSeparateBlocks() throws Exception {var s=store().start(store().createTemplate("运动",true),1,2);s.append("PRE-1","ECG",List.of(Map.of("x",1)));s.append("PRE-2","ECG",List.of(Map.of("x",2)));assertEquals(2,s.counts().size());}
    @Test public void summaryIncludesCountsButDoesNotClaimRawExport() throws Exception {
        var s=store().start(store().createTemplate("步行",true),1,2);
        s.append("CORE-1","HEART_RATE",List.of(Map.of("HEART_RATE",73)));
        s.seal("USER_INTERRUPTED",3);
        String report=new String(ActivityStore.diagnosticSummary(temp.getRoot()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(report.contains("USER_INTERRUPTED"));assertTrue(report.contains("CORE-1/HEART_RATE"));
        assertTrue(report.contains("LOCAL_CAPTURE_METADATA_NOT_RAW_DATA"));assertFalse(report.contains("\"HEART_RATE\":73"));
    }
    @Test public void nullValueRemainsNull() throws Exception {
        var s=store().start(store().createTemplate("步行",false),1,2);
        Map<String,Object> row=new HashMap<>();row.put("IBI_LIST",null);row.put("HEART_RATE_STATUS",-1);
        s.append("CORE-1","HEART_RATE",List.of(row));
        assertTrue(Files.readString(new File(s.folder,"00000001-CORE-1-HEART_RATE.json").toPath()).contains("\"IBI_LIST\":null"));
    }
    @Test public void directorySyncFailureCannotBeReportedAsSuccessfulWrite() throws Exception {
        ActivityStore failing=new ActivityStore(temp.getRoot(),d->{throw new IOException("FSYNC_FAILED");});
        assertThrows(IOException.class,()->failing.createTemplate("步行",true));
    }
}
