import { Component, OnInit, EventEmitter, Output, AfterViewInit, OnDestroy } from '@angular/core';
import { HttpClient } from "@angular/common/http";
import { IslDetailModel } from '../../../common/data-models/isl-detail-model';
import { Observable } from "rxjs";
import { ActivatedRoute, Router } from '@angular/router';
import { IslModel } from "../../../common/data-models/isl-model";
import { SwitchidmaskPipe } from "../../../common/pipes/switchidmask.pipe";
import { IslListService } from '../../../common/services/isl-list.service';
import { DygraphService } from '../../../common/services/dygraph.service';
import { ToastrService } from 'ngx-toastr';
import { IslDataService } from '../../../common/services/isl-data.service';
import { IslDetailService } from '../../../common/services/isl-detail.service';
import { FormBuilder, FormGroup, Validators, NgForm } from '@angular/forms';
import { NgxSpinnerService } from "ngx-spinner";
import { ClipboardService } from "ngx-clipboard";
import * as _moment from 'moment';
import { LoaderService } from "../../../common/services/loader.service";
import { Title } from '@angular/platform-browser';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { ModalconfirmationComponent } from '../../../common/components/modalconfirmation/modalconfirmation.component';
import { IslmaintenancemodalComponent } from '../../../common/components/islmaintenancemodal/islmaintenancemodal.component';
import { CommonService } from '../../../common/services/common.service';
import { ModalComponent } from 'src/app/common/components/modal/modal.component';
import { OtpComponent } from 'src/app/common/components/otp/otp.component';

  declare var moment: any;
  @Component({
    selector: 'app-isl-detail',
    templateUrl: './isl-detail.component.html',
    styleUrls: ['./isl-detail.component.css']
  })

  export class IslDetailComponent implements OnInit, AfterViewInit,OnDestroy {
    openedTab = "graph";
    detailUrl: string = '';
    src_switch:string = '';
    src_port:string = '';
    dst_switch:string = '';
    dst_port:string = '';
    speed:string = '';
    latency:string = '';
    state:string = '';
    evacuate:boolean=false;
    under_maintenance:boolean=false;
    loadingData = true;
    dataSet:any;
    available_bandwidth:string = '';
    default_max_bandwidth='';
    max_bandwidth :any = '';
    detailDataObservable : any;
    src_switch_name: string;
    dst_switch_name: string;	
    graphDataForwardUrl: string;
    graphDataBackwardUrl: string;
    responseGraph = [];
    src_switch_kilda: string;
    dst_switch_kilda:string;
    callGraphAPIFlag: boolean = false;
    currentGraphData = {
        data:[],
        startDate:moment(new Date()).format("YYYY/MM/DD HH:mm:ss"),
        endDate: moment(new Date()).format("YYYY/MM/DD HH:mm:ss"),
        timezone: "LOCAL"
      };
    graphObj: any;
    message:{};
    getautoReloadValues = this.commonService.getAutoreloadValues();

    filterForm: FormGroup;
    graphMetrics = [];
    autoReloadTimerId = null;
    islForm: FormGroup;
    showCostEditing: boolean = false;
    showBandwidthEditing : boolean = false;
    currentGraphName : string = "Round Trip Latency Graph (In Seconds)";
    dateMessage:string;
     clipBoardItems = {
        sourceSwitchName:"",
        sourceSwitch:"",
        targetSwitchName:"",
        targetSwitch:""
        
  }

    @Output() hideToValue: EventEmitter<any> = new EventEmitter();
    newMessageDetail(){
    this.islDataService.changeMessage(this.currentGraphData)
    }


    constructor(private httpClient:HttpClient,
      private route: ActivatedRoute,
      private maskPipe: SwitchidmaskPipe,
      private router:Router,
      private islListService:IslListService,
      private toastr: ToastrService,
      private dygraphService:DygraphService,
      private islDataService: IslDataService,
      private formBuiler: FormBuilder,
      private loaderService: LoaderService,
      private clipboardService: ClipboardService,
      private islFormBuiler: FormBuilder,
      private titleService: Title,
      private modalService: NgbModal,
      private commonService: CommonService,
      private islDetailService : IslDetailService,
    ) {
      
     
    }
    ngOnInit() {
    this.titleService.setTitle('OPEN KILDA - View ISL');
    var date = new Date();
    var yesterday = new Date(date.getTime());
    yesterday.setDate(date.getDate() - 1);
    var fromStartDate = moment(yesterday).format("YYYY/MM/DD HH:mm:ss");
    var toEndDate = moment(date).format("YYYY/MM/DD HH:mm:ss");
    let dateRange = this.getDateRange(); 

    this.route.params.subscribe(params => {
      this.src_switch = params['src_switch'];
      this.src_port = params['src_port'];
      this.dst_switch = params['dst_switch'];
      this.dst_port = params['dst_port'];
      this.src_switch_kilda = this.maskPipe.transform(this.src_switch,'legacy');
      this.dst_switch_kilda = this.maskPipe.transform(this.dst_switch,'legacy');
      this.getIslDetailData(this.src_switch,this.src_port,this.dst_switch,this.dst_port);
    });
   
    this.filterForm = this.formBuiler.group({
      timezone: ["LOCAL"],
      fromDate: [dateRange.from],
      toDate: [dateRange.to],
      download_sample: ["30s"],
      graph: ["rtt"],
      metric: ["bits"],
      auto_reload: [""],
      auto_reload_time: ["", Validators.compose([Validators.pattern("[0-9]*")])]
    });
    this.graphMetrics = this.dygraphService.getPortMetricData();
   

    }

    getIslDetailData(src_switch,src_port,dst_switch,dst_port){
      this.loaderService.show("Loading ISL detail");
      this.islListService.getISLDetailData(src_switch, src_port, dst_switch, dst_port).subscribe((linkData:any) =>{
        if(linkData && linkData.length){
          this.loaderService.hide();
          var retrievedObject = linkData[linkData.length-1];
          console.log('retrievedObject',retrievedObject);
          this.src_switch =retrievedObject.source;
          this.src_switch_name =retrievedObject.source_switch_name;
          this.src_port =retrievedObject.src_port;
          this.dst_switch =retrievedObject.target;
          this.dst_switch_name =retrievedObject.target_switch_name;
          this.dst_port =retrievedObject.dst_port;
          this.speed = retrievedObject.speed;
          this.max_bandwidth = retrievedObject.max_bandwidth;
          this.default_max_bandwidth = retrievedObject.default_max_bandwidth;
          this.latency = retrievedObject.latency;
          this.state = retrievedObject.state;
          this.available_bandwidth = retrievedObject.available_bandwidth;
          this.under_maintenance = retrievedObject.under_maintenance;
          this.evacuate = retrievedObject.evacuate;
          this.clipBoardItems = Object.assign(this.clipBoardItems,{
              sourceSwitchName: retrievedObject.source_switch_name,
              sourceSwitch: retrievedObject.source,
              targetSwitchName: retrievedObject.target_switch_name,
              targetSwitch: retrievedObject.target
            });
            
          
          this.islListService.getIslDetail(this.src_switch, this.src_port, this.dst_switch, this.dst_port).subscribe((data : any) =>{
          if(data!= null){
            this.detailDataObservable = data;
              this.islForm = this.islFormBuiler.group({
              cost: [this.detailDataObservable.cost, Validators.min(0)],
              max_bandwidth:[this.max_bandwidth,Validators.min(0)]
            });
          }
          else{
            this.detailDataObservable = {
              "props": {
              "cost": "-"
              }
              };
    
                this.islForm = this.islFormBuiler.group({
            cost: [this.detailDataObservable.cost, Validators.min(0)],
            max_bandwidth:[this.max_bandwidth,Validators.min(0)]
            });
          }
         },error=>{
           this.toastr.error("API did not return Cost data.",'Error');
         });
        }else{
          this.loaderService.hide();
          this.toastr.error("No ISL Found",'Error');
          this.router.navigate([
            "/isl"
          ]);  
        }     
       // this.setForwardLatency();
        this.loadGraphData();  
      },error =>{
        this.loaderService.hide();
          this.toastr.error("No ISL Found",'Error');
          this.router.navigate([
            "/isl"
          ]);  
      })
    }
    refreshIslFlows(){
        this.getIslFlowList();
    }
      getIslFlowList(){
        this.loadingData = true;
        let query = {src_switch:this.src_switch,src_port:this.src_port,dst_switch:this.dst_switch,dst_port:this.dst_port};
        this.loaderService.show("Loading ISL Flows");
          this.islDetailService.getISLFlowsList(query).subscribe((data : Array<object>) =>{
            this.dataSet = data || [];
            if(this.dataSet.length == 0){
              this.toastr.info("No ISL Flows Available",'Information');
            }else{
              localStorage.setItem('flows',JSON.stringify(data));
            }
            this.loadingData = false;     
          },error=>{
            this.toastr.info("No ISL Flows Available",'Information');
            this.loaderService.hide();
            this.loadingData = false;  
            this.dataSet = [];  
          });
       
      }

     maskSwitchId(switchType, e){
       if(switchType === 'source'){
         if(e.target.checked){
        this.src_switch = this.maskPipe.transform(this.src_switch,'legacy');
      }else{
        this.src_switch = this.maskPipe.transform(this.src_switch,'kilda');
        }
       }
     if(switchType === 'destination'){
      if(e.target.checked){
        this.dst_switch= this.maskPipe.transform(this.dst_switch,'legacy');
      }else{
        this.dst_switch = this.maskPipe.transform(this.dst_switch,'kilda');
      }
    }

       if(switchType == 'source'){
        this.clipBoardItems.sourceSwitch = this.src_switch;
      }else{
        this.clipBoardItems.targetSwitch = this.dst_switch;
      }
    }

    openTab(tab) {
      this.openedTab = tab;
      if(tab == 'graph'){
        this.loadGraphData();
      }else if(tab == 'flow'){
        this.getIslFlowList();
      }
    }

    showMenu(e){
    e.preventDefault();
    $('.clip-board-button').hide();
    $('.clip-board-button').css({
      top: e.pageY+'px',
         left: (e.pageX-220)+'px',
         "z-index":2,
     }).toggle();
     
  }
  islMaintenance(e){
    const modalRef = this.modalService.open(IslmaintenancemodalComponent);
    modalRef.componentInstance.title = "Confirmation";
    modalRef.componentInstance.isMaintenance = !this.under_maintenance;
    modalRef.componentInstance.content = 'Are you sure ?';
    this.under_maintenance = e.target.checked;
    modalRef.result.then((response) =>{
      if(!response){
        this.under_maintenance = false;
      }
    },error => {
      this.under_maintenance = false;
    })
    modalRef.componentInstance.emitService.subscribe(
      evacuate => {
        var data = {src_switch:this.src_switch,src_port:this.src_port,dst_switch:this.dst_switch,dst_port:this.dst_port,under_maintenance:e.target.checked,evacuate:evacuate};
        this.islListService.islUnderMaintenance(data).subscribe(response=>{
          this.toastr.success('Maintenance mode changed successful','Success');
          this.under_maintenance = e.target.checked;
          if(evacuate){
            location.reload();
          }
        },error => {
          this.toastr.error('Error in changing maintenance mode! ','Error');
        })
      },
      error => {
      }
    );
    
  }

  evacuateIsl(e){
    const modalRef = this.modalService.open(ModalconfirmationComponent);
    modalRef.componentInstance.title = "Confirmation";
    this.evacuate = e.target.checked;
    if(this.evacuate){      
     modalRef.componentInstance.content = 'Are you sure you want to evacuate all flows?';
    }else{
      modalRef.componentInstance.content = 'Are you sure ?';
    }
     modalRef.result.then((response)=>{
      if(response && response == true){
        var data = {src_switch:this.src_switch,src_port:this.src_port,dst_switch:this.dst_switch,dst_port:this.dst_port,under_maintenance:this.under_maintenance,evacuate:e.target.checked};
        this.islListService.islUnderMaintenance(data).subscribe(response=>{
          this.toastr.success('All flows are evacuated successfully!','Success');
          location.reload();
        },error => {
          this.toastr.error('Error in evacuating flows! ','Error');
        })
      }else{
        this.evacuate = false;
      }
    },error => {
      this.evacuate = false;
    })
  }

   copyToClip(event, copyItem) {
    this.clipboardService.copyFromContent(this.clipBoardItems[copyItem]);
  }

  changeDate(input, event) {
    this.filterForm.controls[input].setValue(event.target.value);
    setTimeout(() => {

         this.loadGraphData();
      
    }, 0);
  }


    ngAfterViewInit() {
     this.loadGraphData();

    this.filterForm.get("auto_reload").valueChanges.subscribe(value => {
      if (value) {
        this.filterForm
          .get("auto_reload_time")
          .setValidators([Validators.required, Validators.pattern("^[0-9]*")]);
      } else {
        this.filterForm
          .get("auto_reload_time")
          .setValidators([Validators.pattern("^[0-9]*")]);
        if (this.autoReloadTimerId) {
          clearInterval(this.autoReloadTimerId);
        }
      }
      this.filterForm.get("auto_reload_time").setValue("");
      this.filterForm.get("auto_reload_time").updateValueAndValidity();
    });

    this.filterForm.get("auto_reload_time").valueChanges.subscribe(value => {});
    
  }



  startAutoReload() {
    let autoReloadTime = Number(
      this.filterForm.controls["auto_reload_time"].value
    );
    if (this.filterForm.controls["auto_reload"]) {
      if (this.autoReloadTimerId) {
        clearInterval(this.autoReloadTimerId);
      }
      if(autoReloadTime){
        this.autoReloadTimerId = setInterval(() => {
          this.loadGraphData();
       }, 1000 * autoReloadTime);
      }
      
    } else {
      if (this.autoReloadTimerId) {
        clearInterval(this.autoReloadTimerId);
      }
    }
  }


get f() {
    return this.filterForm.controls;
  }

  graphChanged(){
    if(this.filterForm.controls.graph.value == "isllossforward"){
      this.currentGraphName = "ISL Loss Packets Forward Graph";
      this.filterForm.controls.metric.setValue("packets");
    }
    if(this.filterForm.controls.graph.value == "isllossreverse"){
      this.currentGraphName = "ISL Loss Packets Resverse Graph";
      this.filterForm.controls.metric.setValue("packets");
    }
    if(this.filterForm.controls.graph.value == "target"){
      this.currentGraphName = "Destination Graph";
      this.filterForm.controls.metric.setValue("bits");
    }
    if(this.filterForm.controls.graph.value == "source"){
      this.currentGraphName = "Source Graph";
      this.filterForm.controls.metric.setValue("bits");
    }
    if(this.filterForm.controls.graph.value == "latency"){
      this.currentGraphName = "ISL Latency Graph";
    } 
    if(this.filterForm.controls.graph.value == "rtt"){
      this.currentGraphName = "Round Trip Latency Graph (In Seconds)";
    } 
    this.loadGraphData();
  }

   getDateRange() : any {
    var date = new Date();
    var yesterday = new Date(date.getTime());
    yesterday.setDate(date.getDate() - 1);
    var fromStartDate = moment(yesterday).format("YYYY/MM/DD HH:mm:ss");
    var toEndDate = moment(date).format("YYYY/MM/DD HH:mm:ss");

    var utcStartDate = moment(yesterday).utc().format("YYYY/MM/DD HH:mm:ss")
    var utcToEndDate = moment(date).utc().format("YYYY/MM/DD HH:mm:ss");

    return { from : fromStartDate, to : toEndDate ,utcStartDate : utcStartDate,  utcToEndDate : utcToEndDate };
  }

    changeTimezone(){

     let formdata = this.filterForm.value;
    let timezone = formdata.timezone;
    let dateaRange = this.getDateRange();

    if(timezone == "UTC"){
      this.filterForm.controls['fromDate'].setValue(dateaRange.utcStartDate);
      this.filterForm.controls['toDate'].setValue(dateaRange.utcToEndDate);
    }else{
      this.filterForm.controls['fromDate'].setValue(dateaRange.from);
      this.filterForm.controls['toDate'].setValue(dateaRange.to);
    }
    this.loadGraphData();
  }



  loadGraphData(){
     if(this.filterForm.value.graph === 'latency' || this.filterForm.value.graph === 'rtt'){
              this.callGraphAPI();
          }  
        else{
          this.callSourceGraphAPI();
        }
  }

  
  callGraphAPI(){
    
    let formdata = this.filterForm.value;
    let downsampling = formdata.download_sample;
    let autoReloadTime = Number(
      this.filterForm.controls["auto_reload_time"].value
    );
    let metric = formdata.metric;
    let timezone = formdata.timezone;
    let graph = formdata.graph;
    if (this.filterForm.controls["auto_reload"]) {
      formdata.toDate = new Date(new Date(formdata.toDate).getTime() + (autoReloadTime * 1000));
    }
    
    let convertedStartDate = moment(new Date(formdata.fromDate)).add(-60, 'seconds').utc().format("YYYY-MM-DD-HH:mm:ss");
    let convertedEndDate = moment(new Date(formdata.toDate)).add(60, 'seconds').utc().format("YYYY-MM-DD-HH:mm:ss");

    let startDate = moment(new Date(formdata.fromDate));
    let endDate = moment(new Date(formdata.toDate));


    if (
      moment(new Date(formdata.fromDate)).isAfter(new Date(formdata.toDate))
    ) {
      this.toastr.error("Start date can not be after End date", "Error");
      return;
    }


    if (
      moment(new Date(formdata.toDate)).isBefore(new Date(formdata.fromDate))
    ) {
      this.toastr.error("To date should not be less than from date.", "Error");
      return;
    }


    if (formdata.timezone == "UTC") {
      convertedStartDate = moment(new Date(formdata.fromDate)).add(-60, 'seconds').format("YYYY-MM-DD-HH:mm:ss");
      convertedEndDate = moment(new Date(formdata.toDate)).add(60, 'seconds').format("YYYY-MM-DD-HH:mm:ss");
      
    }

      this.dygraphService.getForwardGraphData(this.src_switch_kilda,
                                              this.src_port,
                                              this.dst_switch_kilda,
                                              this.dst_port,
                                              downsampling,
                                              graph,
                                              metric,
                                              convertedStartDate,
                                              convertedEndDate).subscribe((dataForward : any) =>{
                                                
              this.responseGraph = [];
              if(dataForward[0] !== undefined){
              dataForward[0].tags.direction = "F";
              if(graph == 'rtt'){
                var responseData = this.commonService.convertDpsToSecond(dataForward[0]);
                this.responseGraph.push(responseData);
              }else{
                this.responseGraph.push(dataForward[0]) ;
              }
              
          }
        
          this.dygraphService.getBackwardGraphData(this.src_switch_kilda,
                                                    this.src_port,
                                                    this.dst_switch_kilda,
                                                    this.dst_port,
                                                    downsampling,
                                                    graph,
                                                    convertedStartDate,
                                                    convertedEndDate).subscribe((dataBackward : any) =>{
          if(dataBackward[0] !== undefined){
            dataBackward[0].tags.direction = "R";
            if(graph == 'rtt'){
              var responseData = this.commonService.convertDpsToSecond(dataBackward[0]);
              this.responseGraph.push(responseData);
            }else{
              this.responseGraph.push(dataBackward[0]) ;
            }
          }
          this.currentGraphData.data = this.responseGraph;
          this.currentGraphData.timezone = timezone;
          this.currentGraphData.startDate = moment(new Date(formdata.fromDate));
          this.currentGraphData.endDate = moment(new Date(formdata.toDate));
          
          this.newMessageDetail()
          this.islDataService.currentMessage.subscribe(message => this.message = message)
          this.loaderService.hide();
       },error=>{
         this.loaderService.hide();
         this.toastr.error("Backward Graph API did not return data.",'Error');
       });
       },error=>{
          this.loaderService.hide();
          this.toastr.error("Forward Graph API did not return data.",'Error');
         
      });
                              
  }

  // setForwardLatency(){
  //   let formdata = this.filterForm.value;
  //   let downsampling = formdata.download_sample;
  //   let metric = 'latency';
  //   let graph = formdata.graph;
  //   let convertedStartDate = moment(new Date()).add(-300, 'seconds').utc().format("YYYY-MM-DD-HH:mm:ss");
  //   let convertedEndDate = moment(new Date()).add(60, 'seconds').utc().format("YYYY-MM-DD-HH:mm:ss");
  //   let startDate = moment(new Date()).add(-300,'seconds');
  //   let endDate = moment(new Date());
  //   if (formdata.timezone == "UTC") {
  //       convertedStartDate = moment(new Date()).add(-300, 'seconds').format("YYYY-MM-DD-HH:mm:ss");
  //       convertedEndDate = moment(new Date()).add(60, 'seconds').format("YYYY-MM-DD-HH:mm:ss");
  //   }
  //   this.islDetailService.getIslLatencyfromGraph(this.src_switch_kilda,
  //     this.src_port,
  //     this.dst_switch_kilda,
  //     this.dst_port,
  //     convertedStartDate,
  //     convertedEndDate,downsampling).subscribe((dataLatency : any) =>{        
  //     this.responseGraph = [];
  //     if(dataLatency[0] !== undefined){
  //        var data =dataLatency[0].dps;
  //         var latencyTotal = 0;
  //         var dpslength = 1;
  //         var startDateTime = new Date(startDate).getTime()   ;
  //         var endDateTime = new Date(endDate).getTime();
  //         if(dpslength)
  //         Object.keys(data).map(function(d,i){
  //           var dpsTime = new Date(Number(parseInt(d) * 1000)).getTime();
  //           if( dpsTime > startDateTime  && dpsTime < endDateTime){
  //             dpslength = dpslength + 1;
  //             latencyTotal = latencyTotal + parseInt(data[d]);
  //           }
            
  //         });
  //         var avgLatency = latencyTotal / dpslength;
  //         this.latency = avgLatency.toFixed(0).toString();
  //       } 
  //     },error=>{
  //      console.log('Error in getting latency');
  //     });
    


  // }


  callSourceGraphAPI(){
     
    let formdata = this.filterForm.value;
    let downsampling = formdata.download_sample;
    let metric = formdata.metric;
    let timezone = formdata.timezone;
    let graph = formdata.graph;

    let convertedStartDate = moment(new Date(formdata.fromDate)).add(-60, 'seconds').utc().format("YYYY-MM-DD-HH:mm:ss");
    let convertedEndDate = moment(new Date(formdata.toDate)).add(60, 'seconds').utc().format("YYYY-MM-DD-HH:mm:ss");

    let startDate = moment(new Date(formdata.fromDate));
    let endDate = moment(new Date(formdata.toDate));


    if (
      moment(new Date(formdata.fromDate)).isAfter(new Date(formdata.toDate))
    ) {
      this.toastr.error("Start date can not be after End date", "Error");
      return;
    }


    if (
      moment(new Date(formdata.toDate)).isBefore(new Date(formdata.fromDate))
    ) {
      this.toastr.error("To date should not be less than from date.", "Error");
      return;
    }


    if (formdata.timezone == "UTC") {
      convertedStartDate = moment(new Date(formdata.fromDate)).add(-60, 'seconds').format("YYYY-MM-DD-HH:mm:ss");
      convertedEndDate = moment(new Date(formdata.toDate)).add(60, 'seconds').format("YYYY-MM-DD-HH:mm:ss");
      
    }




    this.dygraphService.getForwardGraphData(this.src_switch_kilda,
                                              this.src_port,
                                              this.dst_switch_kilda,
                                              this.dst_port,
                                              downsampling,
                                              graph,
                                              metric,
                                              convertedStartDate,
                                              convertedEndDate).subscribe((dataForward : any) =>{
          
          this.responseGraph = [];
          if(dataForward[0] !== undefined){
            dataForward[0].tags.direction = "F";
            this.responseGraph.push(dataForward[0]) ;
          }
          if(dataForward[1] !== undefined){
            dataForward[1].tags.direction = "R";
            this.responseGraph.push(dataForward[1]) ;
          }
          this.currentGraphData.data = this.responseGraph;
          this.currentGraphData.timezone = timezone;
          this.currentGraphData.startDate = moment(new Date(formdata.fromDate));
          this.currentGraphData.endDate = moment(new Date(formdata.toDate));
          this.loaderService.hide();
          this.newMessageDetail()
          this.islDataService.currentMessage.subscribe(message => this.message = message)
       },error=>{
        this.loaderService.hide();
         this.toastr.error("Backward Graph API did not return data.",'Error');
         
      });
  }


  editCost(){
    this.showCostEditing = true;
    if(this.detailDataObservable.props.cost == "-"){
      this.detailDataObservable.props.cost = "";
    }


     this.islForm.controls["cost"].setValue(
             this.detailDataObservable.props.cost
            );
  }
  editMaxbandwidth(){
    this.showBandwidthEditing = true;
    this.islForm.controls["max_bandwidth"].setValue(this.convertInMB(this.max_bandwidth));
  }
  saveEditedBandwidth(){
    if (this.islForm.invalid) {
      this.toastr.error("Please enter valid value for Max. Bandwidth.");
      return;
    }

    const modalRef = this.modalService.open(ModalconfirmationComponent);
    modalRef.componentInstance.title = "Confirmation";
    modalRef.componentInstance.content = 'Are you sure you want to change the Max Bandwidth?';

    modalRef.result.then((response) => {
      if(response && response == true){
        this.loaderService.show("Updating ISL max bandwidth");
        let costValue = this.convertToByteFromMB(this.islForm.value.max_bandwidth);
        var data = {max_bandwidth:costValue};
        this.islListService.updateIslBandWidth(data,this.src_switch, this.src_port, this.dst_switch, this.dst_port).subscribe((response: any) => {
          this.loaderService.hide();
          this.toastr.success("ISL Bandwidth updated successfully!",'Success');
          this.showBandwidthEditing = false;
          this.max_bandwidth = costValue;
          this.islForm.controls["max_bandwidth"].setValue(costValue);
        },error => {
          this.showBandwidthEditing = false;
          if(error.status == '500'){
            this.toastr.error(error.error['error-message'],'Error! ');
          }else{
            this.toastr.error("Error in updating ISL Bandwidth!",'Error');
          }
        })
      }
    });
  }

  cancelEditedBandwidth(){
    this.showBandwidthEditing = false;
  }

  saveEditedCost(){
    if (this.islForm.invalid) {
      this.toastr.error("Please enter valid value for ISL cost.");
      return;
    }

    const modalRef = this.modalService.open(ModalconfirmationComponent);
    modalRef.componentInstance.title = "Confirmation";
    modalRef.componentInstance.content = 'Are you sure you want to change the cost?';

    modalRef.result.then((response) => {
      if(response && response == true){
        this.loaderService.show("Updating ISL cost");
        let costValue = this.islForm.value.cost;
        this.islListService.updateCost(this.src_switch, this.src_port, this.dst_switch, this.dst_port, costValue).subscribe((status: any) => {
          this.loaderService.hide();

          if(typeof(status.successes)!=='undefined' && status.successes > 0){
            this.toastr.success("ISL cost updated successfully!",'Success');
          
            this.showCostEditing = false;
            this.detailDataObservable.props.cost = costValue;
            this.islForm.controls["cost"].setValue(costValue);
            
          }else if(typeof(status.failures)!=='undefined' && status.failures > 0){
            this.toastr.error("Error in updating ISL cost!",'Error');
            this.showCostEditing = false;
          }


          if(this.detailDataObservable.props.cost == ""){
            this.detailDataObservable.props.cost = "-";
            }

        },error => {
          this.showCostEditing = false;
          if(error.status == '500'){
            this.toastr.error(error.error['error-message'],'Error! ');
          }else{
            this.toastr.error("Error in updating ISL cost!",'Error');
          }
        })
      }
    });
  }

  deleteISL(){
    let is2FaEnabled  = localStorage.getItem('is2FaEnabled')
    var self = this;
    const modalReff = this.modalService.open(ModalconfirmationComponent);
    modalReff.componentInstance.title = "Delete ISL";
    modalReff.componentInstance.content = 'Are you sure you want to perform delete action ?';
    
    modalReff.result.then((response) => {
      if(response && response == true){
        if(is2FaEnabled == 'true'){
          const modalRef = this.modalService.open(OtpComponent);
          modalRef.componentInstance.emitService.subscribe(
            otp => {
              
              if (otp) {
                this.loaderService.show("Deleting ISL");
                var data = {
                  src_switch:this.src_switch,
                  src_port:this.src_port,
                  dst_switch:this.dst_switch,
                  dst_port:this.dst_port,
                  code:otp
                }
                this.modalService.dismissAll();
                this.islListService.deleteIsl(data,response => {
                  this.toastr.success("ISL deleted successfully!", "Success!");
                  this.loaderService.hide();                
                  localStorage.removeItem('ISL_LIST');
                  setTimeout(function(){
                    self.router.navigate(["/isl"]);  
                  },100);
                }, error => {
                  this.loaderService.hide();
                  var message = (error && error['error-auxiliary-message']) ? error['error-auxiliary-message'] :'Error in Isl Deletion!';
                  this.toastr.error(message, "Error!");
                })
              } else {
                this.toastr.error("Unable to detect OTP", "Error!");
              }
            },
            error => {
            }
          );
        }else{
          const modalRef2 = this.modalService.open(ModalComponent);
          modalRef2.componentInstance.title = "Warning";
          modalRef2.componentInstance.content = 'You are not authorised to delete the ISL.';
        }
        
      }
    });
  }

  cancelEditedCost(){
    this.showCostEditing = false;
    if(this.detailDataObservable.props.cost == ""){
      this.detailDataObservable.props.cost = "-";
    }
  }

  zoomHandler=(event, x,y,z)=>{
      
      let formdata = this.filterForm.value;

      if(formdata.timezone == 'UTC'){
        var startDate = moment(new Date(event.minX)).utc().format("YYYY/MM/DD HH:mm:ss");
        var endDate = moment( new Date(event.maxX)).utc().format("YYYY/MM/DD HH:mm:ss");

        this.filterForm.controls['fromDate'].setValue(startDate);
        this.filterForm.controls['toDate'].setValue(endDate);
      }else{
        var startDate = moment(new Date(event.minX)).format("YYYY/MM/DD HH:mm:ss");
        var endDate = moment( new Date(event.maxX)).format("YYYY/MM/DD HH:mm:ss");

        this.filterForm.controls['fromDate'].setValue(startDate);
        this.filterForm.controls['toDate'].setValue(endDate);
      }
  
  }

  convertInMB(value) {
    value = parseInt(value);
    if (value === "" || value == undefined) {
      return "-";
    } else {
      return (value / 1000);
    }
  }

  convertToByteFromMB(value){
    value = parseInt(value);
    return (value * 1000);
  }
  ngOnDestroy(){
    if (this.autoReloadTimerId) {
      clearInterval(this.autoReloadTimerId);
    }
  }

}
  