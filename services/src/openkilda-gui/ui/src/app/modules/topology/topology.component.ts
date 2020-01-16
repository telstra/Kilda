import {
  Component,
  OnInit,
  ViewChild,
  AfterViewInit,
  HostListener,
  ElementRef,
  ViewContainerRef,
  Renderer2,
  OnDestroy
} from "@angular/core";
import { TopologyService } from "../../common/services/topology.service";
import { SwitchService } from "../../common/services/switch.service";
import { UserService } from "../../common/services/user.service";
import { ISL } from "../../common/enums/isl.enum";
import { CommonService } from "../../common/services/common.service";
import * as d3 from "d3";
import { TopologyView } from "../../common/data-models/topology-view";
import { FlowsService } from "../../common/services/flows.service";
import { Observable } from "rxjs";
import { debounceTime, distinctUntilChanged, map } from "rxjs/operators";
import { environment } from "../../../environments/environment";
import { LoaderService } from "../../common/services/loader.service";
import { ToastrService } from "ngx-toastr";
import { Title } from '@angular/platform-browser';
declare var jQuery: any;

@Component({
  selector: "app-topology",
  templateUrl: "./topology.component.html",
  styleUrls: ["./topology.component.css"]
})
export class TopologyComponent implements OnInit, AfterViewInit, OnDestroy {
  @HostListener("blur", ["$event"])
  onBlur(event: Event) {
    event.stopImmediatePropagation();
  }

  nodes = [];
  links = [];
  flows = [];

  searchCase: boolean = false;


  searchView: boolean = false;
  searchModel: any = "";
  searchHidden = false;
  autoRefreshTimerInstance: any;
  canvasTop = 200;
  canvasLeft = 125;
  width: any;
  height: any;
  graphShow=false;
  min_zoom = 0.15;
  scaleLimit = 0.01;
  max_zoom = 3;
  zoomLevel = 0.15;
  zoomStep = 0.1;
  translateX = 0;
  translateY = 0;
  imageObj = new Image();
  linksSourceArr = [];
  new_nodes = false;
  optArray = [];
  size: any;
  forceSimulation: any;
  force: any;
  g: any;
  drag: any;
  svgElement: any;
  zoom: any;
  mLinkNum: any = {};
  isDragMove = true;
  flagHover = true;
  searchedNode = null;
  showSwitchName = false;
  showFlowFlag = false;
  linksData = [];
  displayLinkFlag = false;
  displayNodeFlag = false;

  graphdata = { switch: [], isl: [], flow: [] };

  syncCoordinates = null;

  graphOptions = {
    radius: 35,
    text_center: false,
    nominal_text_size: 10,
    nominal_base_node_size: 40,
    nominal_stroke: 1.5,
    max_stroke: 4.5,
    max_base_node_size: 36,
    max_text_size: 24
  };

  viewOptions: TopologyView;

  graphLink: any;
  graphCircle: any;
  graphText: any;
  graphNode: any;
  graphFlowCount: any;

  graphNodeGroup: any;
  graphLinkGroup: any;
  graphFlowGroup: any;
  canvas:any;
  context:any;
  transform:any;
  constructor(
    private topologyService: TopologyService,
    private switchService: SwitchService,
    private userService: UserService,
    private commonService: CommonService,
    private flowService: FlowsService,
    private renderer: Renderer2,
    private appLoader: LoaderService,
    private toaster :ToastrService,
    private titleService: Title
  ) {}

  ngOnInit() {
    this.titleService.setTitle('OPEN KILDA - Topology');
    this.appLoader.show("Loading Topology");
    this.viewOptions = this.topologyService.getViewOptions();
    let query = {_:new Date().getTime()};
    this.userService.getSettings(query).subscribe(
      coordinates => {
        this.topologyService.setCoordinates(coordinates);
        this.topologyService.setCoordinateChangeStatus('NO');
        this.loadSwitchList();
      },
      error => {
        this.topologyService.setCoordinates(null);
        this.topologyService.setCoordinateChangeStatus('NO');
        this.loadSwitchList();
      }
    );

    this.topologyService.setCoordinateChangeStatus('NO');
    this.topologyService.settingReceiver.subscribe(this.onViewSettingUpdate);
    this.forceSimulation = this.initCanvasSimulation();
   
    this.topologyService.autoRefreshReceiver.subscribe(
      this.onAutoRefreshSettingUpdate
    );

    
  }

  search = (text$: Observable<string>) =>
    text$.pipe(
      debounceTime(200),
      distinctUntilChanged(),
      map(term => term.length < 1 ? []
        : this.optArray.filter(v => v.toLowerCase().indexOf(term.toLowerCase()) > -1).slice(0, 10))
  )
  
  
  initCanvasSimulation(){
     

      this.imageObj.src = environment.assetsPath + "/images/switch.png";
      document.getElementById('graphDiv').style.width =  window.innerWidth + 'px';
      document.getElementById('graphDiv').style.height = window.innerHeight + 'px';
      var graphDiv = document.getElementById('graphDiv').getBoundingClientRect();
      this.canvasTop = graphDiv.top;
      this.canvasLeft = graphDiv.left;
      let width:any = document.getElementById('graphDiv').offsetWidth;
      let height:any = document.getElementById('graphDiv').offsetHeight;
      this.width =  width;
      this.height =  height;
      this.canvas = d3.select('#graphDiv').append('canvas')
      .attr('width', this.width  + 'px')
      .attr('height', this.height  + 'px')
      .node();
      var result = d3.forceSimulation()
              .force("center", d3.forceCenter(this.width / 2, this.height / 2))
              .force("xPos", d3.forceX(this.width /2))
              .force("yPos", d3.forceY(this.height / 2))
              .velocityDecay(0.2)
              .force('collision', d3.forceCollide().radius(function(d) {
                return 20;
              }))
              .force("charge_force",d3.forceManyBody().strength(-1000))
              .force("link", d3.forceLink().strength(1).id(function(d:any) { return d.switch_id; }))
              .alphaTarget(0)
              .alphaDecay(0.05);
        return result;
  }
  
  initGraphCanvas(){
      this.links = this.graphdata.isl || [];
      this.nodes = this.graphdata.switch || [];
      this.context = this.canvas.getContext('2d');
      this.transform = d3.zoomIdentity;
      this.loadCanvasEventListener();
      this.initCanvasGraph(false);
      this.appLoader.show();  
      this.graphShow = false;
      
  }
 
  
  initCanvasGraph(reloadGraph){
    var self = this; 
    this.optArray  = [];
  
    for (var i = 0; i < this.nodes.length; i++) {
      this.optArray.push((this.nodes[i].name));
    }
    this.optArray = this.optArray.sort();
    if (this.nodes.length < 50) {
      this.min_zoom = 0.5;
    }
    if(this.showFlowFlag && !reloadGraph){
      this.links = this.links.concat(this.graphdata.flow);
    }
     
     if (this.links.length > 0) {
      this.linksSourceArr = [];
      try {
        var result = this.commonService.groupBy(this.links, function(item) {
          return [item.source, item.target];
        });
        for (var i = 0, len = result.length; i < len; i++) {
          var row = result[i];
          if (row.length >= 1) {
            for (var j = 0, len1 = row.length; j < len1; j++) {
              if(row[j].hasOwnProperty('flow_count')){
                continue;
              }
              var source_key = (typeof(row[j].source.switch_id) !== 'undefined' ) ? row[j].source.switch_id:row[j].source;
              var target_key = (typeof(row[j].target.switch_id) !== 'undefined' ) ? row[j].target.switch_id:row[j].target;
              var key = source_key + "_" + target_key;
              var key1 = target_key + "_" + source_key;
              var prcessKey = ( this.linksSourceArr && typeof this.linksSourceArr[key] !== "undefined") ? key:key1;
              if (typeof this.linksSourceArr[prcessKey] !== "undefined") {
                this.linksSourceArr[prcessKey].push(row[j]);
              } else {
                this.linksSourceArr[key] = [];
                this.linksSourceArr[key].push(row[j]);
              }
            }
          }
        }
        
      } catch (e) {}
    }
    
    if(this.nodes.length > 50){
        this.scaleLimit = 0.05;
      }
     this.forceSimulation.nodes(this.nodes);
     this.forceSimulation.force("link").links(this.links).distance((d:any)=>{
      let distance = 150;
       try{
      if(!d.flow_count){
        if(d.speed == "40000000"){
          distance = 100;
        }else {
          distance = 300;
        }
       }
       }catch(e){}
       return distance; 
     }).strength(0.1);
     this.forceSimulation.stop();
    this.forceSimulation.on("tick", () => { 
        this.repositionNodes();
        this.simulationUpdate();
    });
    this.zoom = d3.zoom()
                .scaleExtent([this.scaleLimit,this.max_zoom])
                .extent([[0, 0], [this.width, this.height]])
                .on("zoom", this.zoomed)
    d3.select(this.canvas)
      .call(d3.drag().subject(this.dragsubject).on("start", ()=>{ 
        this.dragstarted() ;
      }).on("drag", ()=>{
        this.dragged();
      }).on("end",()=>{
        this.dragended();
      }))
     .call(this.zoom)
     .on('dblclick.zoom',null).on('click.zoom',null);
     this.forceSimulation.restart();
     this.forceSimulation.on("end",()=>{
       this.zoomFit();
       this.onViewSettingUpdate(this.viewOptions, true);
        this.updateCoordinates();
        let positions = this.topologyService.getCoordinates();
        this.topologyService.setCoordinates(positions);
        this.appLoader.hide();  
        this.graphShow = true;      
    })

}
checkIslHover = (x,y) => {
  var self = this;
  var hoverFlag = false;
  var link = null;
  for(var j = 0; j < self.linksData.length; j++ ) {
    link = self.linksData[j];
    var x1 = self.transform.applyX(link.source.x);
    var x2 = self.transform.applyX(link.target.x);
    var y1 = self.transform.applyY(link.source.y);
    var y2 = self.transform.applyY(link.target.y);
    var lineLen = self.dist(x1,y1,x2,y2);
    var d1 = self.dist(x,y, x1,y1);
    var d2 = self.dist(x,y, x2,y2);
    var buffer = 0.1; 
    if(link.arc){
      var islCount = 0;
      var matchedIndex = 1;
      var key = link.source.switch_id + "_" + link.target.switch_id;
      var key1 =  link.target.switch_id + "_" + link.source.switch_id;
      var processKey = ( self.linksSourceArr && typeof self.linksSourceArr[key] !== "undefined") ? key:key1;
      if (self.linksSourceArr && typeof self.linksSourceArr[processKey] !== "undefined") {
      islCount = self.linksSourceArr[processKey].length;
      }
      if (islCount > 1) {
      self.linksSourceArr[processKey].map(function(o, i) {
        if (self.isObjEquivalent(o, link)) {
        matchedIndex = i + 1;
        return;
        }
      });
      }
      var dividend = (1 + (1 / islCount) * ((matchedIndex - 1) - 1));
       var arcPath = new Path2D(self.arcPath(link,link['arc_side'],true,dividend));
       if (self.context.isPointInStroke(arcPath,x,y)) {
         hoverFlag = true;
         break;
       }	
     }else if (d1+d2 >= lineLen-buffer && d1+d2 <= lineLen+buffer) {
      hoverFlag= true;
      break;
    }
  }

  return {'flag':hoverFlag,'link':link};
}

checkNodeHover = (x,y) =>{
  var self = this;
  var hoverflag = false;
  var node = null;
  var nodes =  self.forceSimulation.nodes();
  for(var i = 0; i < nodes.length; i++){
  node  = nodes[i];
  var distX = x - self.transform.applyX(node.x);
  var distY = y - self.transform.applyY(node.y);
  var  distance = Math.sqrt((distX*distX) + (distY*distY));
  if (distance <= self.graphOptions.radius*this.transform.k) {
     hoverflag = true;
      break;
    }
  }
   return {'flag':hoverflag,'node':node};
}
loadCanvasEventListener = () =>{
  var self = this;
  this.canvas.addEventListener('mousemove', function(e) { 
    self.canvas.style.cursor = 'default';
    this.transform = d3.zoomIdentity; 
      const mousePoint = {
       x: e.clientX,
       y: e.clientY
     };  
    
     var linkData = self.checkIslHover(mousePoint.x - self.canvasLeft,mousePoint.y - self.canvasTop);
     var nodeData = self.checkNodeHover(mousePoint.x - self.canvasLeft,mousePoint.y - self.canvasTop); 
     self.displayLinkFlag = linkData.flag;
     self.displayNodeFlag = nodeData.flag;
     if(self.showFlowFlag && linkData.flag && !nodeData.flag){
        self.canvas.style.cursor = 'pointer';
     }else if(self.displayLinkFlag && !self.showFlowFlag && !nodeData.flag){
      self.canvas.style.cursor = 'pointer';
      self.displayLinkTooltip(linkData.link,mousePoint.x,mousePoint.y);
     }else{
      self.displayLinkTooltip(null,0,0);
     }
    
     if(self.displayNodeFlag){
      self.canvas.style.cursor = 'pointer';
      self.displayTooltip(nodeData.node,mousePoint.x,mousePoint.y);
     }else{
      self.displayTooltip(null,0,0);
     }
     return false;
   }, false);

   this.canvas.addEventListener('click', function(e) {  
    e.preventDefault();
    this.transform = d3.zoomIdentity; 
    const mousePoint = {
     x: e.clientX,
     y: e.clientY
   };  
   var linkData = self.checkIslHover(mousePoint.x - self.canvasLeft,mousePoint.y - self.canvasTop);
   var nodeData = self.checkNodeHover(mousePoint.x - self.canvasLeft,mousePoint.y - self.canvasTop);
  if(self.showFlowFlag && linkData.flag && !nodeData.flag){
    self.showFlowDetails(linkData.link);
  } else if(linkData.flag && !self.showFlowFlag && !nodeData.flag){
     self.showLinkDetails(linkData.link);
   }else if(nodeData.flag){
     self.displayFixTooltip(nodeData.node,mousePoint.x,mousePoint.y);
   }
   },false);

   this.canvas.addEventListener('dblclick', function(e) {  
    e.preventDefault();
    this.transform = d3.zoomIdentity; 
    const mousePoint = {
     x: e.clientX,
     y: e.clientY
   };  
   var nodeData = self.checkNodeHover(mousePoint.x - self.canvasLeft,mousePoint.y - self.canvasTop);    
   if(nodeData.flag){
      self.showSwitchDetails(nodeData.node);
   }

   },false);
}

dist = (x1,y1,x2,y2) =>{
    var dx = x2 - x1,
      dy = y2 - y1;
  return Math.sqrt(dx * dx + dy * dy);
}
 displayLinkTooltip = (link,x,y) =>{
    if(link){	
      var availbandwidth = link.available_bandwidth;
       var speed = link.speed;
       var percentage = this.commonService.getPercentage(availbandwidth, speed);
        $("#isl_topology_hover_txt").css("display", "block");
         $("#isl_topology_hover_txt").css("top", (y-30) + "px");
         $("#isl_topology_hover_txt").css("left", (x - 30) + "px");
         $("#topology-hover-text,#switch_hover").css("display", "none");
         var bound = this.horizontallyBound(
          document.getElementById("switchesgraph"),
          document.getElementById("isl_topology_hover_txt")
        );
         $("#isl_topology_hover_txt,#isl_hover").css("display", "block");
          d3.select(".isldetails_div_source_port").html(
            "<span>" +
              (link.src_port == "" || link.src_port == undefined ? "-" : link.src_port) +
              "</span>"
          );
          d3.select(".isldetails_div_destination_port").html(
            "<span>" +
              (link.dst_port == "" || link.dst_port == undefined ? "-" : link.dst_port) +
              "</span>"
          );
          d3.select(".isldetails_div_source_switch").html(
            "<span>" +
              (link.source_switch_name == "" || link.source_switch_name == undefined
                ? "-"
                : link.source_switch_name) +
              "</span>"
          );
          d3.select(".isldetails_div_destination_switch").html(
            "<span>" +
              (link.target_switch_name == "" || link.target_switch_name == undefined
                ? "-"
                : link.target_switch_name) +
              "</span>"
          );
          d3.select(".isldetails_div_speed").html(
            "<span>" +
              (link.speed == "" || link.speed == undefined ? "-" : link.speed / 1000) +
              " Mbps</span>"
          );
          d3.select(".isldetails_div_state").html(
            "<span>" +
              (link.state == "" || link.state == undefined ? "-" : link.state) +
              "</span>"
          );
          d3.select(".isldetails_div_latency").html(
            "<span>" +
              (link.latency == "" || link.latency == undefined ? "-" : link.latency) +
              "</span>"
          );
          d3.select(".isldetails_div_bandwidth").html(
            "<span>" +
              (link.available_bandwidth == "" || link.available_bandwidth == undefined
                ? "-"
                : link.available_bandwidth / 1000) +
              " Mbps (" +
              percentage +
              "%)</span>"
          );
          d3.select(".isldetails_div_unidirectional").html(
            "<span>" +
              (link.unidirectional == "" || link.unidirectional == undefined
                ? "-"
                : link.unidirectional) +
              "</span>"
          );
          d3.select(".isldetails_div_cost").html(
            "<span>" +
              (link.cost == "" || link.cost == undefined ? "-" : link.cost) +
              "</span>"
          );
    }else{
      $("#isl_topology_hover_txt, #isl_hover").css("display", "none");
   }
 }
 displayFixTooltip = (node,x,y) => {
  if(node){
    $("#isl_hover,#isl_topology_hover_txt").css("display", "none");
    $("#topology-hover-txt, #switch_hover").css("display", "none");
    $("#topology-click-txt, #switch_click").css("display", "block");
    $("#topology-click-txt").css("top", (y - 30) + "px");
    $("#topology-click-txt").css("left", (x - 30) + "px");
      d3.select(".switchdetails_div_click_switch_name").html(
        "<span>" + node.name + "</span>"
      );
      d3.select(".switchdetails_div_click_controller").html(
        "<span>" + node.switch_id + "</span>"
      );
      d3.select(".switchdetails_div_click_state").html(
        "<span>" + node.state + "</span>"
      );
      d3.select(".switchdetails_div_click_address").html(
        "<span>" + node.address + "</span>"
      );
      d3.select(".switchdetails_div_click_name").html(
        "<span>" + node.switch_id + "</span>"
      );
      d3.select(".switchdetails_div_click_desc").html(
        "<span>" + node.description + "</span>"
      );
   }else{
     $('#topology-click-txt,#switch_click').css('display','none');
   }

 }
  displayTooltip = (node,x,y) => {
 
   if(node){
    $("#isl_hover,#isl_topology_hover_txt").css("display", "none");
    $("#topology-hover-txt, #switch_hover").css("display", "block");
    $("#topology-hover-txt").css("top", (y - 30) + "px");
    $("#topology-hover-txt").css("left", (x - 30) + "px");
      d3.select(".switchdetails_div_switch_name").html(
        "<span>" + node.name + "</span>"
      );
      d3.select(".switchdetails_div_controller").html(
        "<span>" + node.switch_id + "</span>"
      );
      d3.select(".switchdetails_div_state").html(
        "<span>" + node.state + "</span>"
      );
      d3.select(".switchdetails_div_address").html(
        "<span>" + node.address + "</span>"
      );
      d3.select(".switchdetails_div_name").html(
        "<span>" + node.switch_id + "</span>"
      );
      d3.select(".switchdetails_div_desc").html(
        "<span>" + node.description + "</span>"
      );
   }else{
     $('#topology-hover-txt,#switch_hover').css('display','none');
   }
 }
  	

 arcPath = (d,i,istransform,dividend):any =>{
   var self = this;
   var x1 = (typeof(istransform) !=='undefined' && istransform != null && istransform ) ? this.transform.applyX(d.source.x) : d.source.x,
    y1 = (typeof(istransform) !=='undefined' && istransform != null && istransform ) ? this.transform.applyY(d.source.y) :d.source.y,
    x2 = (typeof(istransform) !=='undefined' && istransform != null && istransform ) ? this.transform.applyX(d.target.x) :d.target.x,
   y2 = (typeof(istransform) !=='undefined' && istransform != null && istransform ) ? this.transform.applyY(d.target.y) :d.target.y,
		dx = x2 - x1,
		dy = y2 - y1,
		dr = Math.sqrt(dx * dx + dy * dy),
		drx = dr/dividend,
    dry = dr/dividend;
  if(i == 0){
		return	"M" +x1 + "," +y1 +"A" +drx +"," +dry +" 0 0 1," +x2 +"," +y2 +"A" +drx +"," +dry +" 0 0 0," +x1 +"," +y1;	
	}else{
		return	"M" +x1 + "," +y1 +"A" +drx +"," +dry +" 0 0 0," +x2 +"," +y2 +"A" +drx +"," +dry +" 0 0 1," +x1 +"," +y1;	
	}   
}

   zoomed = () => {
    this.transform = d3.event.transform;
    this.zoomLevel = Math.round(d3.event.transform.k*100)/100;
    this.simulationUpdate();
  }
  dragsubject = () => {
    var i,
    x = this.transform.invertX(d3.event.x),
    y = this.transform.invertY(d3.event.y),
    dx,
    dy;
    for (i = this.nodes.length - 1; i >= 0; --i) {
      var node = this.nodes[i];
      dx = x - node.x;
      dy = y - node.y;
      if (dx * dx + dy * dy < this.graphOptions.radius * this.graphOptions.radius) {
        return node;
      }
  }
}

getStrokeColor = (d) => {
	var availbandwidth = d.available_bandwidth;
	var speed = d.speed;
	var percentage = this.commonService.getPercentage(availbandwidth, speed);
	if (d.hasOwnProperty("flow_count")) {
	  return "#00baff";
	} else {
	  if (
		(d.unidirectional &&
		  d.state &&
		  d.state.toLowerCase() == "discovered")
	  ) { 
			if (parseInt(percentage) < 50) {
				return "#FF8C00";
			}
		  return ISL.UNIDIR;
	  }else if(d.state && d.state.toLowerCase() == "discovered"){
			if (parseInt(percentage) < 50) {
				return "#FF8C00";
			}
		  return ISL.DISCOVERED;
	  }else if(d.state && d.state.toLowerCase() == "moved") {
		if (parseInt(percentage) < 50) {
				return "#FF8C00";
			  }
		     return ISL.MOVED;
      }
		
  }
	return  ISL.FAILED;
}
plotnodeAndLinks = () =>{
  var self = this;
  self.linksData = [];
  if(typeof(this.searchedNode) == 'undefined' ||  this.searchedNode == null){
        
					var links = self.links || [];
					links.forEach(function(d) {
            d['arc'] = false;
            d['arc_side'] = 0;
            d['is_flow'] = false;
            if(self.showFlowFlag){
              if(d.hasOwnProperty('flow_count')){ 
                d['is_flow'] = true; 
                self.context.beginPath();
                self.context.moveTo(d.source.x, d.source.y);
                self.context.lineTo(d.target.x, d.target.y);
                self.context.strokeStyle = self.getStrokeColor(d);
                self.context.stroke();                
                // adding flow count circle
                 var xvalue = (d.source.y + d.target.y) / 2;
                 var yvalue = (d.source.x + d.target.x) / 2;
                 if(d.source == d.target){
                  var x = (yvalue + 70);
                  var y = (xvalue - 70);
                 }else{
                  var x = yvalue;
                  var y = xvalue;
                 }
                 self.context.beginPath();
                 self.context.arc(x, y, 10, 0, 2 * Math.PI, true);
                 self.context.fillStyle = "#FFF";
                 self.context.strokeStyle = "#00baff";	
                 self.context.lineWidth = 3;				
                 self.context.stroke();
                 self.context.fill();
                // adding flow count text
                self.context.font = "12px Arial";
                self.context.fillStyle = "#000";
                self.context.fillText(d.flow_count,x - 5,y + 5);  
              }
            }else{
              if(d.hasOwnProperty('flow_count')){
                d['isFlow'] = true; 
                return;
              }
             
              var islCount = 0;
              var matchedIndex = 1;
              var key = d.source.switch_id + "_" + d.target.switch_id;
              var key1 =  d.target.switch_id + "_" + d.source.switch_id;
              var processKey = ( self.linksSourceArr && typeof self.linksSourceArr[key] !== "undefined") ? key:key1;
              if (self.linksSourceArr && typeof self.linksSourceArr[processKey] !== "undefined") {
              islCount = self.linksSourceArr[processKey].length;
              }
              if (islCount > 1) {
              self.linksSourceArr[processKey].map(function(o, i) {
                if (self.isObjEquivalent(o, d)) {
                matchedIndex = i + 1;
                return;
                }
              });
              }
              var dividend = (1 + (1 / islCount) * ((matchedIndex - 1) - 1));
              var reverseISL = false;
              var processKeySource = processKey.split("_")[0];
              var processKeyTarget = processKey.split("_")[1];
              if(processKeySource == d.target.switch_id && processKeyTarget == d.source.switch_id){
                reverseISL = true;
              }
              
              self.context.beginPath();
              self.context.moveTo(d.source.x, d.source.y);
              
              self.context.strokeStyle = self.getStrokeColor(d);
              if(d.unidirectional || d.state.toLowerCase() == 'failed' || d.state.toLowerCase() == 'moved'){
                self.context.lineWidth = 6;
              }else{
                self.context.lineWidth = 3;
              }
              if(d.affected){
                self.context.strokeStyle = "#d934CF";
                self.context.setLineDash([10,20]);
              }else{
                self.context.setLineDash([]);
              }
             if(islCount == 1){
              self.context.lineTo(d.target.x, d.target.y);
              self.context.stroke();
             }else{
                 if (islCount % 2 != 0 && matchedIndex == 1) {  
                  self.context.lineTo(d.target.x, d.target.y);
                  self.context.stroke();
                  } else if (matchedIndex % 2 == 0) {  
                    d['arc'] = true;
                    d['arc_side'] = (reverseISL) ? 1 : 0;
                    self.context.stroke(new Path2D(self.arcPath(d, d['arc_side'],null,dividend)));
                  } else {  
                    d['arc'] = true;
                    d['arc_side'] = (reverseISL) ? 0 : 1;
                    self.context.stroke(new Path2D(self.arcPath(d, d['arc_side'],null,dividend)));
                  }
              }
             
            }
           self.linksData.push(d); 								 
        });
         // Draw the nodes
				self.nodes.forEach(function(d, i) {
          self.context.beginPath();
          self.context.setLineDash([]);
          self.context.arc(d.x, d.y, self.graphOptions.radius, 0, 2 * Math.PI, true);
						if(typeof(self.searchedNode) !== 'undefined' && self.searchedNode !== null && d.switch_id == self.searchedNode.switch_id){
              self.context.strokeStyle = "#666";
              self.context.fillStyle = "#CCC";
								setTimeout(function(){								
									self.searchedNode = null;
									self.simulationUpdate();
								},2000);
                self.context.stroke();
                self.context.fill();
						}else{
							self.context.fillStyle = (d.state.toLowerCase() == 'deactivated') ? ISL.FAILED : "#FFF";
							self.context.strokeStyle = "#00baff";
							self.context.stroke();
							self.context.opacity = 0;
							self.context.fill();
						}
						
						
						// adding image to circle		 
            self.context.drawImage(self.imageObj, d.x - 29, d.y-15,'58','30');
					
						// adding switch_name text			
						if(self.showSwitchName){
              self.context.font = "12px Arial";
              self.context.fillStyle = "#000";
              self.context.fillText(d.name,d.x+40,d.y + 5);
						}
						
						
				
				});
			 
  }else{
				// Draw the nodes
				self.nodes.forEach(function(d, i) {
          self.context.beginPath();
          self.context.setLineDash([]);
						self.context.arc(d.x, d.y, self.graphOptions.radius, 0, 2 * Math.PI, true);
						if(typeof(self.searchedNode) !== 'undefined' && self.searchedNode !== null && d.switch_id == self.searchedNode.switch_id){
              self.context.strokeStyle = "#666";
              self.context.fillStyle = "#CCC";
								setTimeout(function(){								
									self.searchedNode = null;
									self.simulationUpdate();
								},2000);
                self.context.stroke();
                self.context.fill();
							// adding image to circle		 
              self.context.drawImage(self.imageObj, d.x - 29, d.y-20,'50','30');
							// adding switch_name text			
							if(self.showSwitchName){
                self.context.font = "12px Arial";
							 self.context.fillStyle = "#000";
							 self.context.fillText(d.name,d.x+40,d.y + 5);
							}		
						}			
						
				});
  }			
}

dragstarted() { 
	if (!d3.event.active) this.forceSimulation.alphaTarget(1).stop();
}

dragged = () => {
  let rect  = this.canvas.getBoundingClientRect();
  jQuery('#isl_topology_hover_txt').hide();
  jQuery('#topology-hover-txt').hide();
  jQuery('#topology-click-txt').hide();
  d3.event.subject.py = ((d3.event.sourceEvent.pageY - rect.top ) - this.transform.y) / this.transform.k;
	d3.event.subject.x = ((d3.event.sourceEvent.pageX - rect.left) - this.transform.x) / this.transform.k;
	d3.event.subject.y = ((d3.event.sourceEvent.pageY - rect.top ) - this.transform.y) / this.transform.k;
	this.simulationUpdate();
}

dragended = () => {	
	if (!d3.event.active) this.forceSimulation.alphaTarget(0);
    this.simulationUpdate();
    this.updateCoordinates();
}

simulationUpdate = () => {
  var self = this;
  this.context.save();
  this.context.clearRect(0, 0, this.width, this.height);
  this.context.translate(this.transform.x, this.transform.y);
  this.context.scale(this.transform.k, this.transform.k);
  this.plotnodeAndLinks();
   self.context.restore();
 }
  

  loadSwitchList = () => {
    this.switchService.getSwitchList().subscribe(switches => {
      this.graphdata.switch = switches || [];
      if(this.graphdata.switch.length > 0){
        this.loadSwitchLinks();
      }else{
        this.toaster.info("No Switch Available","Information");
        this.appLoader.hide();
      }
    },err=>{
      this.appLoader.hide();
      this.toaster.info("No Switch Available","Information");
    });
  };

  loadSwitchLinks = () => {
    this.switchService.getSwitchLinks().subscribe(
      links => {   
        this.showFlowFlag = false;  
        try {    
         if(links){
          this.graphdata.isl = links || [];
          this.topologyService.setLinksData(links);
         }
        
          if (this.viewOptions.FLOW_CHECKED) {
            this.showFlowFlag = true;
            this.loadFlowCount();
          } else {
            this.initGraphCanvas();
          }
        } catch (err) {
          this.initGraphCanvas();
        }
      },
      error => {
        this.loadFlowCount();
      }
    );
  };

  loadFlowCount = () => {
    this.flowService.getFlowCount().subscribe(
      flow => {
        this.graphdata.flow = flow || [];
        this.initGraphCanvas();
      },
      error => {
        this.initGraphCanvas();
      }
    );
  };

  repositionNodes = () => {
    let positions = this.topologyService.getCoordinates();
    if (positions) {
       for(var i = 0; i < this.nodes.length; i++){
        try{
            this.nodes[i].x = positions[this.nodes[i].switch_id][0];
            this.nodes[i].y = positions[this.nodes[i].switch_id][1];
        }catch(e){
        }
      }
    }
  };

 private processNodesData(newNodes, removedNodes, response) {
    this.nodes.forEach(function(d) {
      for (var i = 0, len = response.length; i < len; i++) {
        if (d.switch_id == response[i].switch_id) {
           d.state = response[i].state;
          break;
        }
      }
    });

    if (
      (newNodes && newNodes.length) ||
      (removedNodes && removedNodes.length)
    ) {
      if (newNodes && newNodes.length) {
        this.nodes = this.nodes.concat(newNodes);
        this.new_nodes = true;
      }
      if (removedNodes && removedNodes.length) {
        this.new_nodes = true;
        this.nodes = this.nodes.filter(function(node) {
          var foundFlag = false;
          for (var i = 0; i < removedNodes.length; i++) {
            if (removedNodes[i].switch_id == node.switch_id) {
              foundFlag = true;
              break;
            }
          }
          return !foundFlag;
        });
      }
      if(this.showFlowFlag){        
        this.initCanvasGraph(true);
      }
    } else {
      this.new_nodes = false;
      this.initCanvasGraph(true);
    }
  }

  processLinksData(newLinks, removedLinks, response) {
    this.links.forEach(function(d, index) {
      for (var i = 0, len = response.length; i < len; i++) {
        if (
          d.source.switch_id == response[i].source &&
          d.target.switch_id == response[i].target &&
          d.src_port == response[i].src_port &&
          d.dst_port == response[i].dst_port
        ) {
          d.state = response[i].state;
          d.available_bandwidth = response[i].available_bandwidth;
          d.speed = response[i].speed;
          if (response[i].affected) {
            d["affected"] = response[i].affected;
          } else {
            d["affected"] = false;
          }
          d.unidirectional = response[i].unidirectional;
           break;
        }
      }
    });
    if (
      (newLinks && newLinks.length) ||
      (removedLinks && removedLinks.length) ||
      this.new_nodes
    ) {
      this.new_nodes = false;   
      this.reloadGraphWithNewData(newLinks,removedLinks);
    }else{      
      this.initCanvasGraph(true);
    }
  }

  getSwitchList() {
    this.switchService.getSwitchList().subscribe(
      response => {
        let switchArr: any = [];
        if (this.nodes.length != response.length) {
        // new switch is added
         switchArr = this.getNewSwitch(this.nodes, response);
        }
        var newNodes = switchArr["added"] || [];
        var removedNodes = switchArr["removed"] || [];
        this.processNodesData(newNodes, removedNodes, response);

      },
      error => {
        this.appLoader.hide();
      
      }
    );
  }

  getSwitchLinks() {
    this.switchService.getSwitchLinks().subscribe(
      response => {
        var linksArr: any = [];
        if (this.links.length !== response.length) {
          linksArr = this.getNewLinks(this.links, response);
        }
        var newLinks = linksArr["added"] || [];
        var removedLinks = linksArr["removed"] || [];

        this.processLinksData(newLinks, removedLinks, response);
      },
      error => {}
    );
  }

  /** get removed and newly added switch list */
  getNewSwitch(nodes, response) {
    var nodesArr = { added: [], removed: [] };
    for (var i = 0; i < response.length; i++) {
      var foundFlag = false;
      for (var j = 0; j < nodes.length; j++) {
        if (nodes[j].switch_id == response[i].switch_id) {
          foundFlag = true;
        }
      }
      if (!foundFlag) {
        var newNode = response[i];
        newNode['x'] = this.width / 2;
        newNode['y'] = this.height / 2;
        newNode['vx'] = 0;
        newNode['vy'] = 0;
        nodesArr["added"].push(newNode);
      }
    }
    for (var i = 0; i < nodes.length; i++) {
      var foundFlag = false;
      for (var j = 0; j < response.length; j++) {
        if (response[j].switch_id == nodes[i].switch_id) {
          foundFlag = true;
        }
      }
      if (!foundFlag) {
        nodesArr["removed"].push(nodes[i]);
      }
    }
    return nodesArr;
  }

  /** get removed and newly added switch links  */
  getNewLinks(links, response) {
    var linksArr = { added: [], removed: [] };
    for (var i = 0; i < response.length; i++) {
      var foundFlag = false;
      for (var j = 0; j < links.length; j++) {
        if(links[j].hasOwnProperty('flow_count')){
          continue;
        }
        if (
          links[j].source.switch_id == response[i].source &&
          links[j].target.switch_id == response[i].target &&
          links[j].src_port == response[i].src_port &&
          links[j].dst_port == response[i].dst_port
        ) {
          foundFlag = true;
        }
      }
      if (!foundFlag) {
        linksArr["added"].push(response[i]);
      }
    }
    // checking for removed links
    for (var i = 0; i < links.length; i++) {
      var foundFlag = false;
      for (var j = 0; j < response.length; j++) {
        
        if (
          links[i].source.switch_id == response[j].source &&
          links[i].target.switch_id == response[j].target &&
          links[i].src_port == response[j].src_port &&
          links[i].dst_port == response[j].dst_port
        ) {
          foundFlag = true;
        }
      }
      
      if (!foundFlag && !links[i].hasOwnProperty('flow_count')) {
        linksArr["removed"].push(links[i]);
      }
    }
    return linksArr;
  }

  restartAutoRefreshWithNewSettings(duration) {
    this.autoRefreshTimerInstance = setInterval(() => {
      if(this.viewOptions.FLOW_CHECKED){
        this.getSwitchList();
      }else{
        this.getSwitchList();
        this.getSwitchLinks();
        
      }
    }, duration * 1000);
    this.simulationUpdate();
  }

  isObjEquivalent(a, b) {
    // Create arrays of property names
    var aProps = Object.getOwnPropertyNames(a);
    var bProps = Object.getOwnPropertyNames(b);
    if (aProps.length != bProps.length) {
      return false;
    }

    for (var i = 0; i < aProps.length; i++) {
      var propName = aProps[i];
      if (a[propName] !== b[propName]) {
        return false;
      }
    }
    return true;
  }

  updateCoordinates = () => {
    var coordinates = {};
    this.nodes.forEach(function(d) {
      coordinates[d.switch_id] = [
        Math.round(d.x * 100) / 100,
        Math.round(d.y * 100) / 100
      ];
    });

    this.topologyService.setCoordinates(coordinates);
    this.syncUserCoordinatesChanges();
  };



  horizontallyBound = (parentDiv, childDiv) => {
    let parentRect: any = parentDiv.getBoundingClientRect();
    let childRect: any = childDiv.getBoundingClientRect();
    return (
      parentRect.left <= childRect.left && parentRect.right >= childRect.right
    );
  };

  showFlowDetails = d => {
    let url = "flows?src=" + d.source_switch_name + "&dst=" + d.target_switch_name;
    window.location.href = url;
  };

  showSwitchDetails = d => {
    localStorage.setItem("switchDetailsJSON", JSON.stringify(d));
    window.location.href = "switches/details/" + d.switch_id;
  };

  showLinkDetails = d => {
    localStorage.setItem("linkData", JSON.stringify(d));
    let url = "isl/switch/isl/"+d.source.switch_id+"/"+d.src_port+"/"+d.target.switch_id+"/"+d.dst_port;
    window.location.href = url;
  };

  zoomFn = (direction) => {
   if (direction == 1) {
      this.forceSimulation.stop();
        if (this.zoomLevel + this.zoomStep <= this.max_zoom) {
          this.zoomLevel = this.zoomLevel + this.zoomStep;
          d3.select(this.canvas).transition()
          .duration(350)
          .call(this.zoom.scaleTo, this.zoomLevel + this.zoomStep)
        }
      } else if (direction == -1) {
        this.forceSimulation.stop();
        if (this.zoomLevel - this.zoomStep >= this.scaleLimit) {
        this.zoomLevel  = this.zoomLevel - this.zoomStep;
        d3.select(this.canvas).transition()
          .duration(350)
          .call(this.zoom.scaleTo, this.zoomLevel)
        }
      }  
  }
 
  reloadGraphWithNewData = (newLinks,removedLinks) => {
    var ref = this;
    this.links = this.links.concat(newLinks);
    
    // splice removed links
    if (removedLinks && removedLinks.length) {
      this.links = this.links.filter(function(d) {
        var foundFlag = false;
        for (var i = 0; i < removedLinks.length; i++) {
          if (
            d.source.switch_id == removedLinks[i].source.switch_id &&
            d.target.switch_id == removedLinks[i].target.switch_id &&
            d.src_port == removedLinks[i].src_port &&
            d.dst_port == removedLinks[i].dst_port
          ) {
            foundFlag = true;
            var key = d.source.switch_id + "_" + d.target.switch_id;
            try{  
              ref.linksSourceArr[key].splice(0, 1);
            }catch(err){

            }
            break;
          }
        }
        return !foundFlag;
      });
    }
    if(this.graphdata.flow && this.graphdata.flow.length){
      this.links = this.links.concat(this.graphdata.flow);
    }
    this.forceSimulation = d3.forceSimulation()
                .force("center", d3.forceCenter(this.width / 2, this.height / 2))
                .force("x", d3.forceX(this.width / 2).strength(0.1))
                .force("y", d3.forceY(this.height / 2).strength(0.1))
                .force("charge", d3.forceManyBody().strength(-200))
                .force("link", d3.forceLink().strength(1).id(function(d:any) { return d.switch_id; }))
                .alphaTarget(0)
                .alphaDecay(0.05);
    this.appLoader.show("Re-loading topology with new switch or isl");
    this.graphShow = false;
    this.initCanvasGraph(true);
  }

  randomPoint = (min, max) => {
    let num1 = Math.random() * (max - min) + min;
    var num = Math.floor(Math.random() * 99) + 1;
    return (num1 *= Math.floor(Math.random() * 2) == 1 ? 1 : -1);
  };

  zoomResetCanvas = () =>{
    this.topologyService.setCoordinates(null);
    this.viewOptions = this.topologyService.getViewOptions();
    this.onViewSettingUpdate(this.viewOptions, true);
    this.forceSimulation =  d3.forceSimulation()
    .velocityDecay(0.2)
    .force("center", d3.forceCenter(this.width / 2, this.height / 2))
    .force("xPos", d3.forceX(this.width /2))
    .force("yPos", d3.forceY(this.height / 2))
    .velocityDecay(0.2)
    .force('collision', d3.forceCollide().radius(function(d) {
      return 20;
    }))
    .force("charge_force",d3.forceManyBody().strength(-1000))
    .force("link", d3.forceLink().strength(1).id(function(d:any) { return d.switch_id; }))
    .alphaTarget(0)
    .alphaDecay(0.05);

   this.forceSimulation.nodes(this.nodes);
   this.forceSimulation.force("link").links(this.links).distance((d:any)=>{
    let distance = 150;
     try{
    if(!d.flow_count){
      if(d.speed == "40000000"){
        distance = 100;
      }else {
        distance = 300;
      }
     }
     }catch(e){}
     return distance; 
   }).strength(0.1);
   
   this.forceSimulation.restart();  
   this.forceSimulation.on("tick", () => { 
    this.simulationUpdate();
    this.zoomFit();
     this.updateCoordinates();
     let positions = this.topologyService.getCoordinates();
     this.topologyService.setCoordinates(positions);
  });
   
  }

  zoomFit = () => {	
		var midX = this.width/2;
		var midY = this.height/2;
     this.zoomLevel= this.min_zoom;
     let newtranformation = d3.zoomIdentity
        .scale(this.min_zoom)
        .translate(
        (this.width/2 - this.min_zoom*midX),
        (this.height/2 - this.min_zoom*midY)
        ); 
     if(this.nodes.length >=50){
        newtranformation = d3.zoomIdentity
        .scale(this.scaleLimit)
        .translate(
        (this.width/2 - this.scaleLimit*midX)/this.scaleLimit,
        (this.height/2 - this.scaleLimit*midY)/this.scaleLimit
        ); 
      }
		
	this.transform = newtranformation;
	d3.select(this.canvas).call(d3.zoom().transform,this.transform);
	this.simulationUpdate();
} 
  

  toggleSearch = () => {
    this.searchView = this.searchView ? false : true;
    this.searchModel ='';
    if (this.searchView) {
      const element = this.renderer.selectRootElement("#search-bar");
      setTimeout(() => element.focus(), 0);
      this.searchHidden = false;
    } else {
      this.searchHidden = true;
    }
  };

  searchNode = (selectedVal) => {
    this.searchView = false;
    selectedVal = $.trim(selectedVal);
    this.searchModel = '';
    if ($.inArray(selectedVal, this.optArray) > -1) {
      this.searchedNode = this.nodes.filter(function(d){
                  return d.name  == selectedVal;
                })[0];
      this.simulationUpdate();
    }
  };

  onViewSettingUpdate = (
    setting: TopologyView,
    initialLoad: boolean = false
  ) => { 
    this.viewOptions = this.topologyService.getViewOptions();
    if (setting.SWITCH_CHECKED) {
      this.showSwitchName = true;
    } else {
      this.showSwitchName = false;
    }

    if (setting.ISL_CHECKED) {
      this.showFlowFlag = false;
    } else {
      this.showFlowFlag = true;
    }

    if (setting.FLOW_CHECKED) {
      this.showFlowFlag = true;
      if (!initialLoad && this.graphdata.flow.length == 0) {
        this.graphShow = true;
        window.location.reload();
      }
    } else {
      this.showFlowFlag = false;
     
    }

    this.onAutoRefreshSettingUpdate(setting);
  };

  
  onAutoRefreshSettingUpdate = (setting: TopologyView) => {
    if (this.autoRefreshTimerInstance) {
      clearInterval(this.autoRefreshTimerInstance);
    }

    if (setting.REFRESH_CHECKED) {
      this.restartAutoRefreshWithNewSettings(setting.REFRESH_INTERVAL);
    }else{
      this.simulationUpdate();  
    }
    
  };

  saveCoordinates = () => {
    if(this.topologyService.isCoordinatesChanged()){
        let coordinates = this.topologyService.getCoordinates();
        if (coordinates) {
          this.userService
            .saveSettings(coordinates)
            .subscribe(() => { this.topologyService.setCoordinateChangeStatus('NO');}, error => {});
        }
    }
    
  };

  dblclick = (d, index) => {
    var element = document.getElementById("circle_" + d.switch_id);
    var classes = "circle blue";
    if (d.state && d.state.toLowerCase() == "deactivated") {
      classes = "circle red";
    }
    element.setAttribute("class", classes);
    this.showSwitchDetails(d);
  };

  ngAfterViewInit() {
    setInterval(() => {
      if (this.searchHidden) {
        this.searchView = false;
        this.searchHidden = false;
        this.searchModel = '';
      }
      
    }, 1000);

    this.syncCoordinates = setInterval(() => {
      this.saveCoordinates();
    }, 30000);

    jQuery("#close_switch_detail").click(function() {
      jQuery("#topology-click-txt").css("display", "none");
    });
    
   
  }

  syncUserCoordinatesChanges(){
    if(this.syncCoordinates){
      clearTimeout(this.syncCoordinates);
    }
    this.syncCoordinates = setTimeout(() => {
      this.saveCoordinates();
    }, 1500);
  }

  ngOnDestroy(){
    if(this.autoRefreshTimerInstance){
      clearInterval(this.autoRefreshTimerInstance);
    }

    if(this.syncCoordinates){
      clearTimeout(this.syncCoordinates);
    }


   
  }

}
