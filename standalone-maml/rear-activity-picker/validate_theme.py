#!/usr/bin/env python3
"""Build/validate a bounded MAML picker; no Android/device claims.
The model executes actual XML commands, including ordering and guards.
"""
import argparse
import ast
import copy
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

HERE = Path(__file__).resolve().parent

def build_template():
    root = ET.Element('Screen', frameRate='60', screenWidth='1080', version='2', rearActivityPicker='true')
    def add(parent, tag, **attrs):
        return ET.SubElement(parent, tag, {k: str(v) for k, v in attrs.items()})
    binders = add(root, 'VariableBinders')
    add(binders, 'RearDisplayBinder', name='display')
    for tag, name, focus, accept in [('RearActivityAppsBinder','apps','appIndex','acceptApps'), ('RearActivitiesBinder','activities','activityIndex','acceptActivities')]:
        args = dict(name=name, offsetExp='max(0,#'+focus+'-1)', limit='3', iconSize='64', queryAtStart='false')
        if name == 'activities': args['packageExp'] = '@draftPackage'
        binder = add(binders, tag, **args)
        add(add(binder, 'Trigger'), 'FunctionCommand', target=accept)
    ext = add(root, 'ExternalCommands')
    add(add(ext, 'Trigger', action='init'), 'FunctionCommand', target='initialize')
    values = {
        'scale':'1080/max(1,#display.width)',
        'padding':'6*#scale',
        'navigation':'24*(#display.densityDpi/160)*#scale',
        'safeX':'#display.safeLeft*#scale',
        'safeY':'#display.safeTop*#scale',
        'safeW':'max(0,(#display.width-#display.safeLeft-#display.safeRight)*#scale)',
        'safeH':'max(0,(#display.height-#display.safeTop-#display.safeBottom)*#scale)',
        'pickerX':'#safeX+#padding+#navigation', 'pickerY':'#safeY+#padding',
        'pickerW':'max(0,#safeW-2*#padding-2*#navigation)', 'pickerH':'max(0,#safeH-2*#padding)',
        'displayReady':"gt(#display.count,0)*eq(#display.loading,0)*eqs(@display.error,'')*gt(#display.width,0)*gt(#display.height,0)*ge(#display.safeLeft,0)*ge(#display.safeTop,0)*ge(#display.safeRight,0)*ge(#display.safeBottom,0)*gt(#display.densityDpi,0)*gt(#pickerW,48*#scale)*gt(#pickerH,96*#scale)",
        'liveSelection':"#ra_selected*not(eqs(@ra_launchRequest,''))",
        'pickerVisible':'#ra_ready*not(#liveSelection)*#displayReady',
        'rowH':'#pickerH*0.17', 'fontSize':'min(#pickerW*0.052,#pickerH*0.032)',
        'baseDpi':'max(120,min(960,#display.densityDpi))',
        'stepBase':'ifelse(eq(#draftDpi,0),#display.densityDpi,#draftDpi)',
        'loadReady':"gt(#ra_loadRevision,#loadRevision)*eqs(@ra_loadedPackage,@draftPackage)",
        'appsGood':"#appsReady*eq(#apps.loading,0)*eqs(@apps.error,'')*eq(#apps.offset,#appsOffset)*eq(#apps.requestLimit,3)*eq(#apps.requestIconSize,64)*gt(#apps.revision,#appsRevision)",
        'activitiesGood':"#activitiesReady*eq(#activities.loading,0)*eqs(@activities.error,'')*eqs(@activities.packageName,@draftPackage)*eq(#activities.offset,#activitiesOffset)*eq(#activities.requestLimit,3)*eq(#activities.requestIconSize,64)*gt(#activities.revision,#activitiesRevision)",
    }
    for name, expression in values.items(): add(root, 'Var', name=name, expression=expression)
    numeric = 'stage sequence appIndex activityIndex appsReady activitiesReady appsOffset activitiesOffset appsRevision activitiesRevision draftDpi loadRevision downX downY tapEligible gestureActive downStage downIndex downRevision rowSlot chooseSlot delta'.split()
    for name in numeric: add(root, 'Var', name=name, expression='0', const='true')
    for name in ['draftPackage','draftComponent']:
        add(root, 'Var', name=name, type='string', expression="''", const='true')
    for name in ['appIcons','activityIcons']: add(root, 'Var', name=name, type='object')
    def function(name): return add(root, 'Function', name=name)
    def v(p, name, expression, condition=None, type=None):
        args = dict(name=name, expression=expression)
        if condition: args['condition'] = condition
        if type: args['type'] = type
        return add(p, 'VariableCommand', **args)
    def call(p, target, condition=None):
        args = dict(target=target)
        if condition: args['condition'] = condition
        return add(p, 'FunctionCommand', **args)
    def when(p, condition): return add(add(p, 'IfCommand', ifCondition=condition), 'Consequent')
    def action(p, op, package, component, dpi):
        v(p, 'sequence', 'max(#sequence,#ra_actionSequence)+1')
        command = v(p, 'ra_action', "@ra_instance+'|"+op+"|'+"+package+"+'|'+"+component+"+'|'+"+dpi+"+'|'+#sequence", type='string')
        # Native saveVar hook consumes this command and cancels ConfigFile persistence.
        command.set('persist', 'true')
    f = function('initialize')
    v(f,'stage','0'); v(f,'appIndex','0'); call(f,'queryApps')
    for name, source, index in [('Apps','apps','appIndex'),('Activities','activities','activityIndex')]:
        f = function('query'+name)
        v(f,source+'Ready','0'); v(f,source+'Offset','max(0,#'+index+'-1)')
        v(f,source+'Revision','#'+source+'.revision')
        add(f,'BinderCommand',name=source,command='refresh')
        f = function('accept'+name)
        guard = "eq(#"+source+".loading,0)*eqs(@"+source+".error,'')*eq(#"+source+".offset,#"+source+"Offset)*eq(#"+source+".requestLimit,3)*eq(#"+source+".requestIconSize,64)*gt(#"+source+".revision,#"+source+"Revision)*le(#"+source+".count,3)*ge(#"+source+".count,0)"
        if source == 'activities': guard += '*eqs(@activities.packageName,@draftPackage)'
        v(f,source+'Ready',guard)
        v(f,'appIcons' if source == 'apps' else 'activityIcons',"'"+source+".icons'",condition='#'+source+'Ready',type='object')
    f=function('page')
    p=when(f,'#pickerVisible*eq(#stage,0)*#appsGood')
    v(p,'appIndex','max(0,min(max(0,#apps.total-1),#appIndex+#delta))');call(p,'queryApps')
    p=when(f,'#pickerVisible*eq(#stage,1)*#activitiesGood')
    v(p,'activityIndex','max(0,min(max(0,#activities.total-1),#activityIndex+#delta))');call(p,'queryActivities')
    f=function('pickRow')
    branch=add(f,'IfCommand',ifCondition='eq(#stage,0)')
    apps_branch=add(branch,'Consequent')
    activities_branch=add(branch,'Alternate')
    p=when(apps_branch,"#pickerVisible*eq(#stage,0)*#appsGood*ge(#chooseSlot,0)*lt(#chooseSlot,#apps.count)*not(eqs(@apps.packageNames[#chooseSlot],''))")
    v(p,'draftPackage','@apps.packageNames[#chooseSlot]',type='string')
    v(p,'draftComponent',"''",type='string');v(p,'activityIndex','0');v(p,'loadRevision','#ra_loadRevision')
    v(p,'stage','1');action(p,'load','@draftPackage',"''",'0');call(p,'queryActivities')
    p=when(activities_branch,"#pickerVisible*eq(#stage,1)*#activitiesGood*#loadReady*ge(#chooseSlot,0)*lt(#chooseSlot,#activities.count)*not(eqs(@draftPackage,''))*not(eqs(@activities.components[#chooseSlot],''))")
    v(p,'draftComponent','@activities.components[#chooseSlot]',type='string')
    v(p,'draftDpi','ifelse(#ra_savedFound,#ra_savedDpi,#baseDpi)');v(p,'stage','2')
    f=function('touchDown')
    v(f,'gestureActive','1');v(f,'tapEligible','1');v(f,'downX','#touch_x');v(f,'downY','#touch_y')
    v(f,'downStage','#stage');v(f,'downIndex','ifelse(eq(#stage,0),#appIndex,#activityIndex)')
    v(f,'downRevision','ifelse(eq(#stage,0),#apps.revision,#activities.revision)')
    f=function('touchMove')
    v(f,'tapEligible','0',condition='ge(max(abs(#touch_x-#downX),abs(#touch_y-#downY)),8*#scale)')
    f=function('touchCancel')
    v(f,'tapEligible','0');v(f,'gestureActive','0')
    f=function('releaseMenu')
    call(f,'touchMove')
    p=when(f,'#gestureActive*#pickerVisible*eq(#stage,#downStage)*eq(#downIndex,ifelse(eq(#stage,0),#appIndex,#activityIndex))*eq(#downRevision,ifelse(eq(#stage,0),#apps.revision,#activities.revision))')
    q=when(p,'not(#tapEligible)*ge(abs(#touch_y-#downY),max(12*#scale,#rowH*0.25))*gt(abs(#touch_y-#downY),abs(#touch_x-#downX))')
    v(q,'delta','ifelse(lt(#touch_y,#downY),1,-1)');call(q,'page')
    q=when(p,'#tapEligible')
    v(q,'rowSlot','floor((#downY-#pickerY-#pickerH*0.22)/#rowH)')
    v(q,'chooseSlot','ifelse(eq(#stage,0),#appIndex-#appsOffset,#activityIndex-#activitiesOffset)+#rowSlot-1')
    call(q,'pickRow',condition='ge(#rowSlot,0)*lt(#rowSlot,3)')
    call(f,'touchCancel')
    f=function('adjustDpi')
    v(f,'draftDpi','#stepBase+#delta',condition='#pickerVisible*eq(#stage,2)*ge(#stepBase+#delta,120)*le(#stepBase+#delta,960)')
    f=function('confirm')
    p=when(f,"#pickerVisible*eq(#stage,2)*#loadReady*#activitiesGood*not(eqs(@draftPackage,''))*not(eqs(@draftComponent,''))*(eq(#draftDpi,0)+ge(#draftDpi,120)*le(#draftDpi,960))")
    action(p,'save','@draftPackage','@draftComponent','#draftDpi')
    f=function('back')
    p=when(f,'#pickerVisible*eq(#stage,1)');v(p,'stage','0');call(p,'queryApps')
    p=when(f,'#pickerVisible*eq(#stage,2)');v(p,'stage','1');call(p,'queryActivities')
    # No widgets, ConfigFile persistence, or native selection/launch variable writes.
    add(root,'Rectangle',name='background',fillColor='#FF000000',w='1080',h='#display.height*#scale')
    group=add(root,'Group',x='#pickerX',y='#pickerY',visibility='#displayReady')
    menu=add(group,'Group',visibility='#pickerVisible')
    add(menu,'Text',x='#pickerW/2',y='0',align='center',size='#fontSize',color='#FFFFFFFF',textExp="ifelse(eq(#stage,0),'选择应用',ifelse(eq(#stage,1),'选择 Activity','显示 DPI'))")
    add(menu,'Text',x='0',y='#pickerH*0.055',w='#pickerW',size='#fontSize*0.7',color='#FFCBD9EF',textExp="ifelse(eq(#stage,0),'上下拖动一项，点击条目',@draftPackage)")
    add(menu,'Text',x='0',y='#pickerH*0.095',w='#pickerW',size='#fontSize*0.6',color='#FFCBD9EF',textExp="ifelse(eq(#stage,1)*#loadReady*#ra_savedFound,'上次：'+@ra_savedActivityClass,'')")
    for stage, source, focus, offset, icons, labels in [(0,'apps','appIndex','appsOffset','appIcons','appNames'),(1,'activities','activityIndex','activitiesOffset','activityIcons','activityNames')]:
        rows=add(menu,'Group',visibility='eq(#stage,'+str(stage)+')')
        for row in range(3):
            row_name = source+'Row'+str(row)
            add(root,'Var',name=row_name,expression='#'+focus+'-#'+offset+'+'+str(row)+'-1')
            idx = '#'+row_name
            valid = '#'+source+'Good*ge('+idx+',0)*lt('+idx+',#'+source+'.count)'
            cell=add(rows,'Group',y='#pickerH*0.22+#rowH*'+str(row),visibility=valid)
            add(cell,'Rectangle',w='#pickerW',h='#rowH*0.92',fillColor='#FF263449' if row == 1 else '#FF1C2533')
            add(cell,'Text',x='#rowH*0.3',y='#rowH*0.25',align='center',size='#fontSize',color='#FF94A8C1',text='·')
            add(cell,'Image',x='#rowH*0.08',y='#rowH*0.18',w='#rowH*0.46',h='#rowH*0.46',srcType='BitmapVar',srcExp="ifelse("+valid+",'"+icons+"['+("+idx+")+']','')")
            add(cell,'Text',x='#rowH*0.6',y='#rowH*0.18',w='max(0,#pickerW-#rowH*0.65)',size='#fontSize',color='#FFFFFFFF',textExp='ifelse('+valid+',@'+source+'.'+labels+'['+idx+'],\'\')')
            if stage == 1:
                add(cell,'Text',x='#rowH*0.6',y='#rowH*0.52',w='max(0,#pickerW-#rowH*0.65)',size='#fontSize*0.6',color='#FFAEBFD7',textExp='ifelse('+valid+',@activities.activityClasses['+idx+'],\'\')')
        add(rows,'Text',x='0',y='#pickerH*0.42',size='#fontSize*0.8',w='#pickerW',color='#FFFFFFFF',visibility='not(#'+source+'Good)+eq(#'+source+'.count,0)',textExp="ifelse(#"+source+".loading,'加载中…',ifelse(not(eqs(@"+source+".error,'')),@"+source+".error,ifelse(eq(#"+source+".total,0),'没有可选入口（或应用已卸载）','等待最新目录…')))")
    gesture=add(menu,'Button',name='btn_verticalMenu',x='0',y='#pickerH*0.22',w='#pickerW',h='#rowH*3',interceptTouch='true',visibility='lt(#stage,2)')
    triggers=add(gesture,'Triggers')
    for event,target in [('down','touchDown'),('move','touchMove'),('up','releaseMenu'),('cancel','touchCancel')]: call(add(triggers,'Trigger',action=event),target)
    def button(parent,name,y,label,commands,condition='1',height='#pickerH*0.065'):
        add(parent,'Rectangle',x='0',y=y,w='#pickerW',h=height,fillColor='#FF263449',visibility=condition)
        add(parent,'Text',x='#pickerW/2',y=y,align='center',size='#fontSize*0.85',color='#FFFFFFFF',text=label,visibility=condition)
        # ScreenElementRoot uses one last-write-wins name map for ALL elements.
        # Buttons must never shadow Function targets (back/confirm).
        b=add(parent,'Button',name='btn_'+name,x='0',y=y,w='#pickerW',h=height,interceptTouch='true',visibility=condition)
        t=add(b,'Triggers')
        for event,target in [('down','touchDown'),('move','touchMove'),('cancel','touchCancel')]: call(add(t,'Trigger',action=event),target)
        up=add(t,'Trigger',action='up');call(up,'touchMove')
        p=when(up,'#gestureActive*#tapEligible*eq(#stage,#downStage)*('+condition+')')
        for name_,expr in commands:
            if name_ == 'call': call(p,expr)
            else: v(p,name_,expr)
        call(up,'touchCancel')
    button(menu,'previous','#pickerH*0.14','▲ 上一项',[('delta','-1'),('call','page')],condition='lt(#stage,2)')
    button(menu,'next','#pickerH*0.75','▼ 下一项',[('delta','1'),('call','page')],condition='lt(#stage,2)')
    button(menu,'back','#pickerH*0.84','返回',[('call','back')],condition='gt(#stage,0)')
    # Non-clipping, non-touchable container: reverse-order child dispatch must
    # fall through to the return button outside the DPI controls' bounds.
    dpi=add(menu,'Group',visibility='eq(#stage,2)',touchable='false',clip='false')
    add(dpi,'Text',x='#pickerW/2',y='#pickerH*0.14',align='center',color='#FFFFFFFF',size='#fontSize*1.6',textExp="ifelse(eq(#draftDpi,0),'继承 (0)','#'+#draftDpi)")
    for i, delta in enumerate([-10,-1,1,10]):
        button(dpi,'dpi'+str(delta).replace('-','minus'),'#pickerH*'+str(0.24+i*0.095),str(delta) if delta<0 else '+'+str(delta),[('delta',str(delta)),('call','adjustDpi')],condition='ge(#stepBase+'+str(delta)+',120)*le(#stepBase+'+str(delta)+',960)',height='#pickerH*0.08')
    button(dpi,'inherit','#pickerH*0.63','继承显示密度 (0)',[('draftDpi','0')])
    button(dpi,'confirm','#pickerH*0.73','确认并启动',[('call','confirm')],condition='#pickerVisible*#loadReady')
    add(menu,'Text',x='0',y='#pickerH*0.92',w='#pickerW',size='#fontSize*0.6',color='#FFFFABAB',textExp='@ra_error')
    selected=add(root,'Group',x='#safeX',y='#safeY',visibility='#ra_ready*#liveSelection*#displayReady',touchable='false',clip='false')
    add(selected,'ActivityHost',name='selectedActivity',x='0',y='0',w='#safeW',h='#safeH',navigationGutterDp='0',showControls='false',componentExp='@ra_component',dpiExp='#ra_dpi',userRequestExp='@ra_launchRequest',instanceExp='@ra_instance',visibility='#ra_ready*#liveSelection*#displayReady')
    add(root,'Text',x='#safeX',y='#safeY',w='#safeW',size='24',color='#FFFFFFFF',visibility='not(#displayReady)+not(#ra_ready)',text='等待卡片身份与有效背屏安全区域')
    # MAML derived Var values are tick-cached. Events must use current source fields
    # and mutable drafts directly, not last-frame Good/loadReady/stepBase values.
    for element in root.iter():
        if element.tag not in {'IfCommand','VariableCommand','FunctionCommand'}:continue
        for key in ['ifCondition','condition','expression']:
            expression=element.get(key)
            if expression is None:continue
            for name in ['pickerVisible','appsGood','activitiesGood','loadReady','stepBase','baseDpi','liveSelection']:
                expression=expression.replace('#'+name,'('+values[name]+')')
            element.set(key,expression)
    ET.indent(root,space='    ')
    return ET.tostring(root,encoding='unicode',xml_declaration=True)+'\n'

# Small expression interpreter: values/commands are taken from XML, not duplicated
# state transitions. Its scope is deterministic expressions used by this template.
class Model:
    def __init__(self, root):
        self.root=root; self.data={}; self.formulas={}
        # Model the shared ScreenElementRoot map, not a Function-only dictionary:
        # a later Button with the same name makes FunctionCommand a silent no-op.
        element_tags={'Function','Button','Var','Group','Rectangle','Text','Image','ActivityHost'}
        self.elements={e.get('name'):e for e in root.iter() if e.tag in element_tags and e.get('name')}
        self.pressed_buttons=set()
        self.actions=[]; self.queries=[]; self.records={}; self.selected=None; self.fail_save=False; self.launches=[]
        for v in root.findall('Var'):
            if v.get('const')=='true' or not v.get('expression'): self.data[v.get('name')]='' if v.get('type')=='string' else 0
            else: self.formulas[v.get('name')]=v.get('expression')
        self.data.update({'display.count':1,'display.loading':0,'display.error':'','display.width':1080,'display.height':720,'display.safeLeft':296,'display.safeRight':0,'display.safeTop':0,'display.safeBottom':0,'display.densityDpi':320,'ra_ready':1,'ra_selected':0,'ra_instance':'instance','ra_launchRequest':'','ra_actionSequence':0,'ra_loadRevision':0,'ra_error':'','ra_loadedPackage':'','ra_savedFound':0,'ra_savedDpi':0,'ra_savedComponent':'','ra_savedActivityClass':''})
        for source in ['apps','activities']:
            self.data.update({source+'.'+k:0 for k in ['loading','revision','count','offset','total','requestLimit','requestIconSize']})
            self.data[source+'.error']=''; self.data[source+'.packageName']=''
    def get(self,name):
        return self.eval(self.formulas[name]) if name in self.formulas else self.data.get(name,0)
    def eval(self,expression):
        expression=re.sub(r'[#@]([\w.]+)',lambda m:'var('+repr(m[1])+')',expression)
        def node(n):
            if isinstance(n,ast.Constant): return n.value
            if isinstance(n,ast.UnaryOp):
                a=node(n.operand);return -a if isinstance(n.op,ast.USub) else a
            if isinstance(n,ast.BinOp):
                a,b=node(n.left),node(n.right)
                if isinstance(n.op,ast.Add): return str(a)+str(b) if isinstance(a,str) or isinstance(b,str) else a+b
                if isinstance(n.op,ast.Sub):return a-b
                if isinstance(n.op,ast.Mult):return a*b
                if isinstance(n.op,ast.Div):return a/b
            if isinstance(n,ast.Subscript):
                seq,idx=node(n.value),int(node(n.slice))
                return seq[idx] if isinstance(seq,list) and 0<=idx<len(seq) else ''
            if isinstance(n,ast.Call):
                name=n.func.id;args=[node(a) for a in n.args]
                ops={'var':self.get,'max':max,'min':min,'abs':abs,'floor':lambda x:int(x//1),'eq':lambda a,b:int(a==b),'ne':lambda a,b:int(a!=b),'eqs':lambda a,b:int(a==b),'gt':lambda a,b:int(a>b),'ge':lambda a,b:int(a>=b),'lt':lambda a,b:int(a<b),'le':lambda a,b:int(a<=b),'NOT':lambda a:int(not a),'ifelse':lambda c,a,b:a if c else b}
                return ops[name](*args)
            raise ValueError(ast.dump(n))
        return node(ast.parse(expression.replace('not(','NOT('),mode='eval').body)
    def run(self,name):
        target=self.elements.get(name)
        if target is not None and target.tag=='Function':self.execute(target)
    def button_shapes(self):
        """Bounded v2 Group traversal: reverse order, translated child bounds.

        This models this template's non-clipping/non-touchable Groups only;
        it is not an Android renderer or a full MAML input implementation.
        """
        shapes=[]
        def visit(parent,x=0,y=0):
            for e in reversed(list(parent)):
                if e.tag not in {'Group','Button'}:continue
                if not self.eval(e.get('visibility','1')):continue
                left=x+self.eval(e.get('x','0'));top=y+self.eval(e.get('y','0'))
                if e.tag=='Group':
                    assert e.get('touchable','false')=='false' and e.get('clip','false')=='false'
                    visit(e,left,top)
                else:shapes.append((e,left,top,self.eval(e.get('w')),self.eval(e.get('h'))))
        visit(self.root)
        return shapes
    def touch(self,event,x,y):
        self.data.update(touch_x=x,touch_y=y)
        for b,left,top,width,height in self.button_shapes():
            name=b.get('name');inside=left<=x<left+width and top<=y<top+height
            if event=='down':
                if not inside:continue
                self.pressed_buttons.add(name);action='down'
            elif name in self.pressed_buttons:
                action='cancel' if event=='cancel' or (event=='up' and not inside) else event
                if event in {'up','cancel'}:self.pressed_buttons.remove(name)
            else:continue
            self.execute(b.find("Triggers/Trigger[@action='"+action+"']"))
            if event!='move' or inside:return name
        return None
    def execute(self,parent):
        for c in parent:
            if c.get('condition') and not self.eval(c.get('condition')):continue
            if c.tag=='VariableCommand':
                value=self.eval(c.get('expression'));name=c.get('name')
                if name=='ra_action':self.native(value)
                elif c.get('type')=='object':self.data[name]=self.get(value)
                else:self.data[name]=value
            elif c.tag=='FunctionCommand':self.run(c.get('target'))
            elif c.tag=='IfCommand':
                branch=c.find('Consequent') if self.eval(c.get('ifCondition')) else c.find('Alternate')
                if branch is not None:self.execute(branch)
            elif c.tag=='BinderCommand':self.queries.append((c.get('name'),self.get('draftPackage'),self.get('appIndex') if c.get('name')=='apps' else self.get('activityIndex')))
    def native(self,action):
        instance,op,package,component,dpi,sequence=action.split('|');dpi=int(dpi);sequence=int(sequence)
        assert instance==self.get('ra_instance') and sequence>self.get('ra_actionSequence')
        assert not self.actions or sequence>int(self.actions[-1].split('|')[-1])
        self.data['ra_actionSequence']=sequence
        self.actions.append(action)
        if op=='load':
            record=self.records.get(package)
            saved_class=''
            if record and '/' in record[0]:
                saved_package,saved_class=record[0].split('/',1)
                if saved_class.startswith('.'):saved_class=saved_package+saved_class
            self.data.update(ra_loadedPackage=package,ra_loadRevision=self.get('ra_loadRevision')+1,ra_savedFound=int(record is not None),ra_savedDpi=record[1] if record else 0,ra_savedComponent=record[0] if record else '',ra_savedActivityClass=saved_class)
        elif op=='save':
            if self.fail_save:self.data['ra_error']='atomic_save_failed';return
            self.records[package]=(component,dpi)
            self.selected=(package,component,dpi)
            self.data.update(ra_selected=1,ra_package=package,ra_component=component,ra_dpi=dpi)
            # Publish only after successful store and selected state.
            request=instance+'|'+component+'|'+str(dpi)+'|'+str(sequence)
            self.data['ra_launchRequest']=request
            self.launches.append((self.selected,request))
        elif op=='reset':self.data['ra_selected']=0;self.selected=None
    def publish(self,source,offset=0,total=8,package=None,revision=None):
        revision=self.get(source+'.revision')+1 if revision is None else revision
        count=min(3,max(0,total-offset))
        self.data.update({source+'.'+k:v for k,v in {'loading':0,'error':'','revision':revision,'count':count,'offset':offset,'total':total,'requestLimit':3,'requestIconSize':64,'hasMore':int(offset+count<total),'icons':[None]*count}.items()})
        if source=='apps':
            self.data['apps.packageNames']=['p'+str(i) for i in range(offset,offset+count)]
            self.data['apps.appNames']=['App '+str(i) for i in range(offset,offset+count)]
        else:
            package=package or self.get('draftPackage');self.data['activities.packageName']=package
            self.data['activities.activityClasses']=[package+'.Activity'+str(i) for i in range(offset,offset+count)]
            self.data['activities.components']=[package+'/'+class_name for class_name in self.data['activities.activityClasses']]
            self.data['activities.activityNames']=['Activity '+str(i) for i in range(offset,offset+count)]
        self.run('acceptApps' if source=='apps' else 'acceptActivities')

class Tests(unittest.TestCase):
    def setUp(self): self.m=Model(ET.parse(HERE/'manifest.xml').getroot());self.m.run('initialize');self.m.publish('apps')
    def choose_app(self): self.m.data['chooseSlot']=0;self.m.run('pickRow');self.m.publish('activities')
    def choose_activity(self):self.choose_app();self.m.data['chooseSlot']=0;self.m.run('pickRow')
    def test_pages_and_stale(self):
        for index in range(1,8):
            self.m.data['delta']=1;self.m.run('page');self.assertEqual(self.m.get('appIndex'),index)
            old=self.m.get('appsRevision');self.m.publish('apps',max(0,index-2),revision=old);self.assertFalse(self.m.get('appsGood'))
            self.m.publish('apps',max(0,index-1));self.assertTrue(self.m.get('appsGood'))
        self.m.data['delta']=1;self.m.run('page');self.assertEqual(self.m.get('appIndex'),7)
    def test_package_loading_and_unknown_guards(self):
        self.choose_app();self.m.publish('activities',package='wrong');self.m.data['chooseSlot']=0;self.m.run('pickRow');self.assertEqual(self.m.get('stage'),1)
        self.m.publish('activities');self.m.data['activities.loading']=1;self.m.run('pickRow');self.assertEqual(self.m.get('stage'),1)
        self.m.data['activities.loading']=0;self.m.data['chooseSlot']=8;self.m.run('pickRow');self.assertEqual(self.m.get('stage'),1)
    def test_drag_one_step_and_cancel(self):
        y=self.m.get('pickerY')+self.m.get('pickerH')*.22+self.m.get('rowH')*1.5
        self.m.data.update(touch_x=self.m.get('pickerX')+50,touch_y=y);self.m.run('touchDown')
        self.m.data['touch_y']=y-80;self.m.run('touchMove');self.m.run('releaseMenu')
        self.assertEqual(self.m.get('appIndex'),1);self.assertFalse(self.m.actions)
        self.m.publish('apps');self.m.data['touch_y']=y;self.m.run('touchDown');self.m.run('touchCancel');self.m.run('releaseMenu');self.assertFalse(self.m.actions)
    def test_click_chooses_current_row(self):
        self.m.data.update(touch_x=self.m.get('pickerX')+50,touch_y=self.m.get('pickerY')+self.m.get('pickerH')*.22+self.m.get('rowH')*1.5)
        self.m.run('touchDown');self.m.run('releaseMenu');self.assertEqual(self.m.get('draftPackage'),'p0');self.assertEqual(len(self.m.actions),1)
    def test_revision_changes_mid_touch_reject_tap(self):
        self.m.data.update(touch_x=500,touch_y=300);self.m.run('touchDown');self.m.publish('apps');self.m.run('releaseMenu');self.assertFalse(self.m.actions)
    def test_dpi_restore_inherit_bounds(self):
        self.m.records['p0']=('p0/p0.Activity0',0);self.choose_activity();self.assertEqual(self.m.get('draftDpi'),0)
        self.m.data['delta']=-1;self.m.run('adjustDpi');self.assertEqual(self.m.get('draftDpi'),319)
        for start,delta,expected in [(120,-1,120),(120,-10,120),(960,1,960),(960,10,960),(320,-10,310),(320,10,330),(320,1,321)]:
            self.m.data.update(draftDpi=start,delta=delta);self.m.run('adjustDpi');self.assertEqual(self.m.get('draftDpi'),expected)
    def test_readiness_and_geometry(self):
        self.choose_activity();self.m.data['ra_ready']=0;self.m.run('confirm');self.assertFalse(self.m.launches)
        self.m.data['ra_ready']=1;self.m.data['display.width']=320;self.m.run('confirm');self.assertFalse(self.m.launches)
        self.assertFalse(self.m.get('displayReady'))
    def test_save_failure_retry_order_and_restore_no_launch(self):
        self.choose_activity();self.m.fail_save=True;self.m.run('confirm');self.assertFalse(self.m.launches);self.assertEqual(self.m.get('stage'),2);self.assertEqual(self.m.get('ra_selected'),0)
        self.m.fail_save=False;self.m.run('confirm');self.assertEqual(self.m.launches[0][0],self.m.selected);self.assertEqual(self.m.records['p0'],('p0/p0.Activity0',320))
        self.m.run('confirm');self.assertEqual(len(self.m.launches),1)
        restored=Model(self.m.root);restored.data.update(ra_selected=1,ra_package='p0',ra_component='p0/p0.Activity0',ra_dpi=320);restored.run('initialize');self.assertFalse(restored.actions);self.assertFalse(restored.launches)
    def test_unauthed_restore_shows_picker_without_automatic_launch_and_confirm_is_fresh_save(self):
        self.m.data.update(ra_selected=1,ra_package='p0',ra_component='p0/p0.Activity0',ra_dpi=320,ra_launchRequest='')
        self.m.records['p0']=('p0/p0.Activity0',320)
        self.m.run('initialize');self.m.publish('apps')
        self.assertTrue(self.m.get('pickerVisible'));self.assertTrue(self.m.button_shapes())
        host=self.m.root.find('.//ActivityHost')
        self.assertFalse(self.m.eval(host.get('visibility')))
        self.assertFalse(self.m.actions);self.assertFalse(self.m.launches)
        self.choose_activity();self.tap_button('btn_confirm')
        self.assertEqual([action.split('|')[1] for action in self.m.actions],['load','save'])
        self.assertEqual(len(self.m.launches),1);self.assertTrue(self.m.get('ra_launchRequest'))
        self.assertTrue(self.m.eval(host.get('visibility')));self.assertEqual(self.m.button_shapes(),[])
    def test_live_selection_event_guard_ignores_tick_cached_visibility(self):
        self.choose_activity()
        original=self.m.get
        frozen={name:original(name) for name in ['pickerVisible','liveSelection']}
        self.m.get=lambda name:frozen[name] if name in frozen else original(name)
        self.m.data.update(ra_selected=1,ra_launchRequest='instance|p0/p0.Activity0|320|5')
        self.m.run('confirm');self.assertFalse(self.m.launches);self.assertFalse(self.m.records)
        self.m.data['ra_launchRequest']=''
        frozen.update(pickerVisible=0,liveSelection=1)
        self.m.run('confirm');self.assertEqual(len(self.m.launches),1)
        for element in self.m.root.iter():
            if element.tag in {'IfCommand','VariableCommand','FunctionCommand'}:
                for value in element.attrib.values():
                    self.assertNotIn('#pickerVisible',value);self.assertNotIn('#liveSelection',value)
    def test_restored_save_failure_stays_in_menu_without_launch_permit(self):
        self.m.data.update(ra_selected=1,ra_component='p0/p0.Activity0',ra_dpi=320,ra_launchRequest='')
        self.choose_activity();self.m.fail_save=True;self.tap_button('btn_confirm')
        self.assertTrue(self.m.get('pickerVisible'));self.assertTrue(self.m.button_shapes())
        self.assertEqual(self.m.get('ra_launchRequest'),'');self.assertFalse(self.m.launches)
        self.assertFalse(self.m.eval(self.m.root.find('.//ActivityHost').get('visibility')))
    def test_back_keeps_draft_unsaved_and_selected_has_no_reset_control(self):
        self.choose_activity();self.m.run('back');self.assertEqual(self.m.get('stage'),1);self.assertFalse(self.m.records)
        self.m.publish('activities');self.m.data['chooseSlot']=0;self.m.run('pickRow');self.m.run('confirm')
        self.assertEqual(self.m.get('ra_selected'),1);self.assertIn('p0',self.m.records)
        self.assertIsNone(self.m.root.find(".//Button[@name='btn_reselect']"))
        self.assertIsNone(self.m.root.find("Function[@name='reselect']"))
    def test_physical_insets_not_double_added(self):
        self.assertEqual(self.m.get('safeX'),296);self.assertEqual(self.m.get('pickerX'),350);self.assertEqual(self.m.get('navigation'),48)
    def test_reinit_uses_native_nonce_floor(self):
        self.m.data.update(sequence=0,ra_actionSequence=10)
        self.m.run('initialize');self.m.publish('apps');self.m.data['chooseSlot']=0;self.m.run('pickRow')
        self.assertEqual(self.m.get('sequence'),11)
        self.assertEqual(self.m.get('ra_actionSequence'),11)
        self.assertEqual(self.m.actions[-1].split('|')[-1],'11')
        self.m.publish('activities');self.m.data['chooseSlot']=0;self.m.run('pickRow');self.m.run('confirm')
        self.assertEqual(self.m.get('sequence'),12)
    def test_tick_cached_vars_never_reenter_or_bypass_loading(self):
        self.choose_app();self.m.data['stage']=0
        frozen={name:self.m.get(name) for name in ['appsGood','activitiesGood','loadReady','stepBase']}
        original=self.m.get
        self.m.get=lambda name:frozen[name] if name in frozen else original(name)
        self.m.data['chooseSlot']=0;self.m.run('pickRow')
        self.assertEqual(self.m.get('stage'),1)  # app click cannot also choose Activity
        self.m.data['activities.loading']=1;self.m.run('pickRow');self.assertEqual(self.m.get('stage'),1)
        self.m.publish('activities');self.m.run('pickRow');self.assertEqual(self.m.get('stage'),2)
        self.m.data.update(draftDpi=320,delta=1);self.m.run('adjustDpi');self.m.run('adjustDpi');self.assertEqual(self.m.get('draftDpi'),322)
    def test_dpi_confirm_button_drag_is_not_save(self):
        self.choose_activity();button=self.m.root.find(".//Button[@name='btn_confirm']")
        self.m.data.update(touch_x=500,touch_y=540)
        self.m.execute(button.find("Triggers/Trigger[@action='down']"))
        self.m.data['touch_y']=570;self.m.execute(button.find("Triggers/Trigger[@action='up']"));self.assertFalse(self.m.records);self.assertFalse(self.m.launches)
    def button_point(self,name):
        for b,x,y,w,h in self.m.button_shapes():
            if b.get('name')==name:return x+w/2,y+h/2
        self.fail('visible button missing: '+name)
    def tap_button(self,name):
        x,y=self.button_point(name)
        self.assertEqual(self.m.touch('down',x,y),name)
        self.assertEqual(self.m.touch('up',x,y),name)
    def test_global_element_names_do_not_shadow_function_targets(self):
        elements=[e for e in self.m.root.iter() if e.tag in {'Function','Button','Var','Group','Rectangle','Text','Image','ActivityHost'} and e.get('name')]
        names=[e.get('name') for e in elements]
        self.assertEqual(len(names),len(set(names)))
        for command in self.m.root.iter('FunctionCommand'):
            self.assertEqual(self.m.elements[command.get('target')].tag,'Function')
    def test_old_button_names_reproduce_real_maml_noop(self):
        root=copy.deepcopy(self.m.root)
        for name in ['back','confirm']:
            root.find(".//Button[@name='btn_"+name+"']").set('name',name)
        legacy=Model(root);legacy.data.update(stage=2,draftPackage='p0',draftComponent='p0/p0.Activity0')
        for name in ['back','confirm']:
            self.assertEqual(legacy.elements[name].tag,'Button')
            legacy.run(name)
        self.assertEqual(legacy.get('stage'),2);self.assertFalse(legacy.actions)
        with self.assertRaises(AssertionError):validate_structure(root)
    def test_dpi_return_up_hit_path_queries_activities_never_saves(self):
        self.choose_activity();actions=list(self.m.actions);queries=len(self.m.queries)
        self.tap_button('btn_back')
        self.assertEqual(self.m.get('stage'),1)
        self.assertEqual(len(self.m.queries),queries+1)
        self.assertEqual(self.m.queries[-1][0],'activities')
        self.assertEqual(self.m.actions,actions);self.assertFalse(self.m.records);self.assertFalse(self.m.launches)
        self.m.publish('activities');self.tap_button('btn_back')
        self.assertEqual(self.m.get('stage'),0);self.assertEqual(self.m.queries[-1][0],'apps')
    def test_dpi_return_drag_cancel_and_outside_up_do_not_act(self):
        for event in ['move','cancel','outside']:
            with self.subTest(event=event):
                self.setUp();self.choose_activity();actions=list(self.m.actions);queries=list(self.m.queries)
                x,y=self.button_point('btn_back');self.m.touch('down',x,y)
                if event=='move':
                    self.m.touch('move',x+12*self.m.get('scale'),y)
                    self.m.touch('up',x,y)
                elif event=='cancel':
                    self.m.touch('cancel',x,y);self.m.touch('up',x,y)
                else:self.m.touch('up',x,y+100*self.m.get('scale'))
                self.assertEqual(self.m.get('stage'),2);self.assertEqual(self.m.actions,actions)
                self.assertEqual(self.m.queries,queries);self.assertFalse(self.m.records)
                self.assertEqual(self.m.get('gestureActive'),0)
    def test_button_up_without_down_and_stage_change_rejected(self):
        self.choose_activity();x,y=self.button_point('btn_back')
        self.assertIsNone(self.m.touch('up',x,y));self.assertEqual(self.m.get('stage'),2)
        self.m.touch('down',x,y);self.m.data['stage']=1
        self.m.touch('up',x,y);self.assertEqual(self.m.get('stage'),1)
        self.assertFalse(self.m.records);self.assertEqual(self.m.get('gestureActive'),0)
    def test_confirm_hit_path_selected_mode_has_no_touch_buttons(self):
        self.choose_activity();self.tap_button('btn_confirm')
        self.assertEqual(self.m.selected,('p0','p0/p0.Activity0',320))
        self.assertEqual(len(self.m.launches),1)
        self.assertEqual(self.m.button_shapes(),[])
        self.assertIsNone(self.m.touch('down',500,100))
        self.assertIsNone(self.m.touch('up',500,100))
        self.assertEqual(self.m.get('ra_selected'),1);self.assertIn('p0',self.m.records)
    def test_selected_host_full_safe_rect_black_background_without_controls(self):
        host=self.m.root.find(".//ActivityHost")
        for width,height,insets in [(976,596,(296,0,0,0)),(904,572,(180,7,5,9)),(596,976,(0,296,0,0))]:
            with self.subTest(size=(width,height)):
                left,top,right,bottom=insets  # Pro inset is synthetic, not a device claim.
                self.m.data.update({'display.width':width,'display.height':height,'display.safeLeft':left,'display.safeTop':top,'display.safeRight':right,'display.safeBottom':bottom,'display.densityDpi':450,'ra_selected':1,'ra_launchRequest':'instance|p0/p0.Activity0|450|1'})
                scale=self.m.get('scale')
                self.assertAlmostEqual(self.m.get('safeX')/scale,left)
                self.assertAlmostEqual(self.m.get('safeY')/scale,top)
                self.assertAlmostEqual(self.m.eval(host.get('w'))/scale,width-left-right)
                self.assertAlmostEqual(self.m.eval(host.get('h'))/scale,height-top-bottom)
                self.assertEqual(host.get('x'),'0');self.assertEqual(host.get('y'),'0')
                self.assertEqual(host.get('showControls'),'false');self.assertEqual(host.get('navigationGutterDp'),'0')
                self.assertEqual(self.m.button_shapes(),[])
        background=self.m.root.find("Rectangle[@name='background']")
        self.assertEqual(background.get('fillColor'),'#FF000000')
        self.assertEqual(self.m.eval(background.get('w')),1080)
        self.assertAlmostEqual(self.m.eval(background.get('h'))/scale,height)
    def test_confirm_hit_path_keeps_readiness_guard(self):
        self.choose_activity();x,y=self.button_point('btn_confirm')
        self.m.touch('down',x,y);self.m.data['activities.loading']=1
        self.m.touch('up',x,y);self.assertFalse(self.m.records);self.assertFalse(self.m.launches)
    def test_activity_class_display_and_component_save_abi(self):
        self.choose_app()
        labels=[e for e in self.m.root.iter('Text') if '@activities.activityClasses[' in e.get('textExp','')]
        self.assertEqual(len(labels),3)
        displayed=[self.m.eval(e.get('textExp')) for e in labels]
        self.assertIn('p0.Activity0',displayed)
        self.assertTrue(all('/' not in value for value in displayed))
        self.assertFalse(any('@activities.components' in e.get('textExp','') for e in self.m.root.iter('Text')))
        self.m.data['chooseSlot']=0;self.m.run('pickRow');self.tap_button('btn_confirm')
        self.assertEqual(self.m.records['p0'],('p0/p0.Activity0',320))
        self.assertEqual(self.m.actions[-1].split('|')[3],'p0/p0.Activity0')
    def test_saved_activity_display_is_full_class_not_component(self):
        saved=next(e for e in self.m.root.iter('Text') if '@ra_savedActivityClass' in e.get('textExp',''))
        for component in ['p0/p0.Activity0','p0/.Activity0']:
            with self.subTest(component=component):
                self.setUp();self.m.records['p0']=(component,320);self.choose_app()
                self.assertEqual(self.m.get('ra_savedComponent'),component)
                self.assertEqual(self.m.eval(saved.get('textExp')),'上次：p0.Activity0')
        self.assertFalse(any('@ra_savedComponent' in e.get('textExp','') for e in self.m.root.iter('Text')))
    def test_generated_manifest_matches_checked_in_xml(self):
        self.assertEqual((HERE/'manifest.xml').read_text(encoding='utf-8'),build_template())


def validate_structure(root):
    assert root.tag=='Screen' and root.get('rearActivityPicker')=='true'
    assert root.get('rearWidgetPicker') is None
    binders=root.find('VariableBinders')
    assert [b.tag for b in binders]==['RearDisplayBinder','RearActivityAppsBinder','RearActivitiesBinder']
    for b in list(binders)[1:]:
        assert b.get('limit')=='3' and b.get('iconSize')=='64' and b.get('queryAtStart')=='false'
    assert binders.find('RearActivitiesBinder').get('packageExp')=='@draftPackage'
    functions={f.get('name') for f in root.findall('Function')}
    element_tags={'Function','Button','Var','Group','Rectangle','Text','Image','ActivityHost'}
    names=[e.get('name') for e in root.iter() if e.tag in element_tags and e.get('name')]
    assert len(names)==len(set(names)), 'Global ScreenElement names must be unique'
    for c in root.iter('FunctionCommand'): assert c.get('target') in functions
    for c in root.iter('VariableCommand'):
        if c.get('name','').startswith('ra_'):
            assert c.get('name')=='ra_action' and c.get('type')=='string' and c.get('persist')=='true'
        else:assert not c.get('persist')
    assert not any(v.get('name','').startswith('ra_') for v in root.findall('Var'))
    assert len(list(root.iter('ActivityHost')))==1
    host=next(root.iter('ActivityHost'))
    for key,value in {'componentExp':'@ra_component','dpiExp':'#ra_dpi','userRequestExp':'@ra_launchRequest','instanceExp':'@ra_instance','x':'0','y':'0','w':'#safeW','h':'#safeH','showControls':'false','navigationGutterDp':'0'}.items():assert host.get(key)==value
    assert host.get('showControlsExp') is None
    assert root.find("Rectangle[@name='background']").get('fillColor')=='#FF000000'
    parents={child:parent for parent in root.iter() for child in parent}
    selected=parents[host]
    assert selected.get('x')=='#safeX' and selected.get('y')=='#safeY'
    assert selected in list(root) and not list(selected.iter('Button'))
    assert root.find(".//Button[@name='btn_reselect']") is None
    images=list(root.iter('Image'));assert len(images)==6 and all(i.get('srcType')=='BitmapVar' for i in images)
    assert not any('Widget' in e.tag for e in root.iter())
    assert all(e.get('name')=='ra_action' for e in root.iter() if e.get('persist'))
    for b in root.iter('Button'):
        assert {t.get('action') for t in b.findall('Triggers/Trigger')}=={'down','move','up','cancel'}
    # Parse/evaluate every expression under default values, catching ABI syntax mistakes.
    m=Model(root)
    for e in root.iter():
        for key,value in e.attrib.items():
            if key in {'expression','condition','ifCondition','srcExp','textExp','offsetExp','packageExp','dpiExp','componentExp','userRequestExp','instanceExp','visibility'}:
                m.eval(value)


def package():
    output=HERE/'rear-activity-picker.zip'
    with ZipFile(output,'w',compression=ZIP_DEFLATED,compresslevel=9) as z:
        entry=ZipInfo('manifest.xml',(2020,1,1,0,0,0));entry.compress_type=ZIP_DEFLATED;entry.external_attr=0o100644<<16
        z.writestr(entry,(HERE/'manifest.xml').read_bytes())
    first=output.read_bytes()
    with ZipFile(output) as z:
        assert z.namelist()==['manifest.xml'] and z.testzip() is None
        assert z.read('manifest.xml')==(HERE/'manifest.xml').read_bytes()
    with ZipFile(output,'w',compression=ZIP_DEFLATED,compresslevel=9) as z:
        entry=ZipInfo('manifest.xml',(2020,1,1,0,0,0));entry.compress_type=ZIP_DEFLATED;entry.external_attr=0o100644<<16
        z.writestr(entry,(HERE/'manifest.xml').read_bytes())
    assert first==output.read_bytes()
    # Keep the existing distribution outside the source directory in sync.
    published=HERE.parent/'rear-activity-picker.zip'
    if published.exists():
        published.write_bytes(first)
        assert published.read_bytes()==first
        with ZipFile(published) as z:
            assert z.namelist()==['manifest.xml'] and z.testzip() is None
            assert z.read('manifest.xml')==(HERE/'manifest.xml').read_bytes()
        print('Existing standalone-maml/rear-activity-picker.zip synchronized: PASS')
    print('ZIP root/CRC/reproducibility: PASS')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--write-template',action='store_true');parser.add_argument('--package',action='store_true');args=parser.parse_args()
    if args.write_template:(HERE/'manifest.xml').write_text(build_template(),encoding='utf-8',newline='\n')
    root=ET.parse(HERE/'manifest.xml').getroot();validate_structure(root)
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests))
    if not result.wasSuccessful():raise SystemExit(1)
    print('XML schema/command graph/expression structure: PASS')
    if args.package:package()
